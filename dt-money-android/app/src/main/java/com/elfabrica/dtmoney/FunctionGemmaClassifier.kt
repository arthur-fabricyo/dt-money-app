package com.elfabrica.dtmoney

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class FunctionGemmaClassifier(private val app: Context) : BankCategoryClassifier {
    override suspend fun suggest(context: CategoryContext, categories: List<Category>): CategorySuggestion =
        withContext(Dispatchers.IO) { engineLock.withLock { suggestCategory(context, categories) } }

    private fun suggestCategory(context: CategoryContext, categories: List<Category>): CategorySuggestion {
        val model = modelFile(app)
        if (!model.isFile) return CategorySuggestion.Unavailable
        return try {
            Engine(EngineConfig(
                modelPath = model.absolutePath, backend = Backend.CPU(),
                maxNumTokens = 1024, cacheDir = app.cacheDir.absolutePath
            )).use { engine ->
                engine.initialize()
                val config = ConversationConfig(
                    tools = listOf(tool(CategoryTool())), automaticToolCalling = false, maxOutputToken = 64
                )
                engine.createConversation(config).use { conversation ->
                    val call = conversation.sendMessage(
                        categoryPrompt(context, categories) + "\nChame sugerirCategoria com o ID escolhido."
                    ).toolCalls.singleOrNull()
                    val id = call?.takeIf { it.name == "sugerirCategoria" }?.arguments?.get("categoriaId")
                    val categoryId = when (id) {
                        is String -> id
                        is Number -> id.toInt().takeIf { it.toDouble() == id.toDouble() }?.toString()
                        else -> null
                    }
                    validateCategory(categoryId, categories)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CategorySuggestion.Unavailable
        } catch (_: LinkageError) {
            CategorySuggestion.Unavailable
        }
    }

    suspend fun interpret(input: PreparedBankAlert, categories: List<Category>): BankModelResult =
        withContext(Dispatchers.IO) {
            val model = modelFile(app)
            if (!model.isFile) return@withContext BankModelResult.Unavailable
            engineLock.withLock {
                try {
                    Engine(EngineConfig(
                        modelPath = model.absolutePath, backend = Backend.CPU(),
                        maxNumTokens = 2048, cacheDir = app.cacheDir.absolutePath
                    )).use { engine ->
                        engine.initialize()
                        val config = ConversationConfig(
                            tools = listOf(tool(BankAlertTool())), automaticToolCalling = false, maxOutputToken = 224
                        )
                        engine.createConversation(config).use { conversation ->
                            val call = conversation.requestBankTool(
                                bankAlertPrompt(input, categories) + "\nChame interpretarAlerta."
                            )
                            bankModelResult(call?.takeIf { it.name == "interpretarAlerta" }?.arguments)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BankModelResult.Unavailable
                } catch (_: LinkageError) {
                    BankModelResult.Unavailable
                }
            }
        }

    companion object {
        private val engineLock = Mutex()
        private const val MAX_MODEL_BYTES = 512L * 1024 * 1024
        private fun modelFile(context: Context) = File(context.noBackupFilesDir, "functiongemma-270m.litertlm")

        fun installed(context: Context): Boolean = modelFile(context).isFile

        fun install(context: Context, uri: Uri) {
            val target = modelFile(context)
            val pending = File.createTempFile("functiongemma-", ".tmp", target.parentFile)
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Arquivo indisponível")
                input.use { stream ->
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_MODEL_BYTES) throw IOException("Modelo maior que o limite")
                            output.write(buffer, 0, read)
                        }
                        if (total == 0L) throw IOException("Arquivo vazio")
                    }
                }
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
    }
}

private suspend fun Conversation.requestBankTool(prompt: String): ToolCall? =
    suspendCancellableCoroutine { continuation ->
        val calls = mutableListOf<ToolCall>()
        val finished = AtomicBoolean(false)
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) { calls.addAll(message.toolCalls) }
            override fun onDone() {
                if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.success(calls.singleOrNull()))
            }
            override fun onError(throwable: Throwable) {
                if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(throwable))
            }
        }
        sendMessageAsync(prompt, callback)
        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) runCatching { cancelProcess() }
        }
    }

internal class CategoryTool : ToolSet {
    @Tool(description = "Sugere um ID de categoria do catálogo ou 0 se não for possível inferir.")
    fun sugerirCategoria(@ToolParam(description = "ID inteiro da categoria ou 0") categoriaId: Int): Int = categoriaId
}

internal class BankAlertTool : ToolSet {
    @Tool(description = "Propõe os dados de uma movimentação bancária concluída ou se abstém.")
    @Suppress("UNUSED_PARAMETER")
    fun interpretarAlerta(
        @ToolParam(description = "CONCLUIDO ou ABSTER") estado: String,
        @ToolParam(description = "Valor em centavos ou 0") centavos: Double,
        @ToolParam(description = "INCOME ou EXPENSE") direcao: String,
        @ToolParam(description = "PIX ou PURCHASE") tipo: String,
        @ToolParam(description = "PIX, CREDIT, DEBIT ou UNKNOWN") meio: String,
        @ToolParam(description = "Valor com R$ copiado do alerta") valorNoAlerta: String,
        @ToolParam(description = "Trecho literal que comprova a conclusão e direção") eventoNoAlerta: String,
        @ToolParam(description = "ID de categoria do catálogo ou 0") categoria: Int
    ): String = estado
}
