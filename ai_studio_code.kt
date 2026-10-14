package com.example

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// 1. DATA ENTITIES & ROOM DATABASE
// ==========================================

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    val rank: String = "Apex Guardian",
    val avatarUrl: String = "",
    val isOnline: Boolean = true
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isGroup: Boolean = false,
    val isAi: Boolean = false,
    val lastMessage: String = "",
    val lastTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val avatarColor: Long = 0xFF6366F1
)

@Entity(
    tableName = "messages",
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean = false,
    val messageType: String = "text", // "text", "image", "video", "animation_3d", "animation_2d"
    val mediaUrl: String? = null,
    val mediaThumbnail: String? = null
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastTimestamp DESC")
    fun getAllChatsFlow(): Flow<List<ChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatById(chatId: String): ChatEntity?
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChatFlow(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesForChat(chatId: String)
}

@Database(entities = [UserEntity::class, ChatEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mera_meet.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}

// ==========================================
// 2. REMOTE CLIENT (GEMINI & VIDEO/ANIMATION)
// ==========================================

object GeminiClient {
    private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    suspend fun generateChatReply(prompt: String, apiKey: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "Mera AI here! To enable live Gemini neural responses, add GEMINI_API_KEY to your secrets."
        }
        try {
            val url = URL("$GEMINI_API_URL?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                val contents = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                }
                put("contents", contents)
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(response)
                val text = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                return@withContext text
            }
            "AI Service encountered HTTP ${conn.responseCode}. Falling back to offline assistant."
        } catch (e: Exception) {
            "Mera AI Assistant: I'm here to help you collaborate, generate videos, and convert files!"
        }
    }

    suspend fun handleVideoGeneration(
        prompt: String,
        is3D: Boolean = false,
        is2D: Boolean = false
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        delay(1200) // Simulated neural render pipeline
        val mediaType = when {
            is3D -> "animation_3d"
            is2D -> "animation_2d"
            else -> "video"
        }
        val videoUrl = when {
            is3D -> "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
            is2D -> "https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
            else -> "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        }
        Pair(videoUrl, mediaType)
    }
}

// ==========================================
// 3. REPOSITORY & BUSINESS LOGIC
// ==========================================

class ChatRepository(private val db: AppDatabase) {
    val allChats = db.chatDao().getAllChatsFlow()

    fun getMessagesForChat(chatId: String) = db.messageDao().getMessagesForChatFlow(chatId)

    suspend fun seedInitialData() {
        val existing = db.chatDao().getChatById("chat_ai_mera")
        if (existing == null) {
            val defaultChats = listOf(
                ChatEntity("chat_ai_mera", "Mera AI Assistant", isGroup = false, isAi = true, "Ask me anything or create videos!", avatarColor = 0xFF8B5CF6),
                ChatEntity("chat_dev_team", "Dev Team General", isGroup = true, isAi = false, "Sprint review scheduled at 3 PM", avatarColor = 0xFF3B82F6),
                ChatEntity("chat_sarah", "Sarah Connor", isGroup = false, isAi = false, "Sent the latest UI kit", avatarColor = 0xFFEC4899)
            )
            db.chatDao().insertAll(defaultChats)
            db.messageDao().insertMessage(
                MessageEntity(chatId = "chat_ai_mera", senderId = "ai", senderName = "Mera AI", content = "Welcome to Mera Meet! I am your AI co-pilot. You can type '/video <prompt>' to generate realistic videos or animations.", isFromMe = false)
            )
        }
    }

    suspend fun sendMessage(chatId: String, text: String, apiKey: String = "") {
        val userMsg = MessageEntity(
            chatId = chatId,
            senderId = "me",
            senderName = "You",
            content = text,
            isFromMe = true
        )
        db.messageDao().insertMessage(userMsg)

        // Mera AI Response trigger
        if (chatId == "chat_ai_mera") {
            val lower = text.lowercase()
            if (lower.contains("video") || lower.contains("animation") || lower.startsWith("/video")) {
                val is3d = lower.contains("3d")
                val is2d = lower.contains("2d") || lower.contains("anime")
                val (videoUrl, mediaType) = GeminiClient.handleVideoGeneration(text, is3d, is2d)
                db.messageDao().insertMessage(
                    MessageEntity(
                        chatId = chatId,
                        senderId = "ai",
                        senderName = "Mera AI",
                        content = "Here is your generated ${if (is3d) "3D animation" else if (is2d) "2D animation" else "realistic video"}! ✨",
                        isFromMe = false,
                        messageType = mediaType,
                        mediaUrl = videoUrl
                    )
                )
            } else {
                val reply = GeminiClient.generateChatReply(text, apiKey)
                db.messageDao().insertMessage(
                    MessageEntity(chatId = chatId, senderId = "ai", senderName = "Mera AI", content = reply, isFromMe = false)
                )
            }
        }
    }
}

// ==========================================
// 4. VIEWMODEL
// ==========================================

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ChatRepository(AppDatabase.getInstance(application))

    val chats: StateFlow<List<ChatEntity>> = repo.allChats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentChatId = MutableStateFlow("chat_ai_mera")
    val currentChatId: StateFlow<String> = _currentChatId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentMessages: StateFlow<List<MessageEntity>> = _currentChatId
        .flatMapLatest { repo.getMessagesForChat(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allChannelIds = chats.map { list -> list.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupChannelIds = chats.map { list -> list.filter { it.isGroup }.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aiChannelIds = chats.map { list -> list.filter { it.isAi }.map { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repo.seedInitialData() }
    }

    fun selectChat(id: String) {
        _currentChatId.value = id
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repo.sendMessage(_currentChatId.value, text, "")
        }
    }
}

// ==========================================
// 5. JETPACK COMPOSE UI COMPONENTS
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeraMeetApp(viewModel: ChatViewModel = viewModel()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats by viewModel.chats.collectAsState()
    val currentChatId by viewModel.currentChatId.collectAsState()
    val currentChat = chats.find { it.id == currentChatId }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7))), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Mera Channels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Apex Guardian Active", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(chats) { chat ->
                        NavigationDrawerItem(
                            label = {
                                Text(chat.name, fontWeight = if (chat.id == currentChatId) FontWeight.Bold else FontWeight.Normal)
                            },
                            icon = {
                                Icon(
                                    imageVector = if (chat.isAi) Icons.Default.AutoAwesome else if (chat.isGroup) Icons.Default.Group else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(chat.avatarColor)
                                )
                            },
                            selected = chat.id == currentChatId,
                            onClick = {
                                viewModel.selectChat(chat.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(currentChat?.name ?: "Mera Meet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (currentChat?.isAi == true) "AI Assistant • 60fps Video Gen" else "Online", fontSize = 12.sp, color = Color.Gray)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Channels")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Tag, contentDescription = "Switch Channel")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        ) { innerPadding ->
            ChatScreenContent(
                modifier = Modifier.padding(innerPadding),
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun ChatScreenContent(modifier: Modifier = Modifier, viewModel: ChatViewModel) {
    val messages by viewModel.currentMessages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }

        // Input bar
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Message or '/video <prompt>'...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = inputText
                        inputText = ""
                        viewModel.sendMessage(text)
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: MessageEntity) {
    val isMe = msg.isFromMe
    val align = if (isMe) Alignment.End else Alignment.Start
    val bgColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bgColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isMe) {
                    Text(msg.senderName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                }
                Text(msg.content, color = textColor)

                // Video / 3D / 2D Card Preview
                if (msg.messageType.contains("video") || msg.messageType.contains("animation")) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .background(Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = "Play Video",
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when (msg.messageType) {
                                    "animation_3d" -> "✨ 3D Realistic Animation • 60fps"
                                    "animation_2d" -> "🎨 2D Cel Animation • HD"
                                    else -> "🎥 Photorealistic Video • 1080p"
                                },
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MeraMeetApp()
            }
        }
    }
}