package com.elfabrica.dtmoney

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CancellationException

internal class GeminiNanoClassifier : BankCategoryClassifier {
    override suspend fun suggest(context: CategoryContext, categories: List<Category>): CategorySuggestion {
        return try {
            val model = Generation.getClient()
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return CategorySuggestion.Unavailable
            val response = model.generateContent(
                categoryPrompt(context, categories) + "\nResponda somente com o ID inteiro, ou 0."
            )
            validateCategory(response.candidates.singleOrNull()?.text, categories)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CategorySuggestion.Unavailable
        }
    }
}
