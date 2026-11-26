package com.elfabrica.dtmoney

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FunctionGemmaClassifier(private val app: Context) : BankCategoryClassifier {
    override suspend fun suggest(context: CategoryContext, categories: List<Category>): CategorySuggestion =
        withContext(Dispatchers.IO) {
            val model = modelFile(app)
            if (!model.isFile) return@withContext CategorySuggestion.Unavailable
            try {
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
                        val id = call?.takeIf { it.name == "sugerirCategoria" }
                            ?.arguments?.get("categoriaId")
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

    companion object {
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

internal class CategoryTool : ToolSet {
    @Tool(description = "Sugere um ID de categoria do catálogo ou 0 se não for possível inferir.")
    fun sugerirCategoria(@ToolParam(description = "ID inteiro da categoria ou 0") categoriaId: Int): Int = categoriaId
}
