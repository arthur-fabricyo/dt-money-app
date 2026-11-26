package com.elfabrica.dtmoney

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

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

    suspend fun interpret(input: PreparedBankAlert, categories: List<Category>): BankModelResult {
        return try {
            val model = Generation.getClient()
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return BankModelResult.Unavailable
            val response = model.generateContent(bankAlertPrompt(input, categories) +
                "\nResponda somente com JSON com as chaves estado, centavos, direcao, tipo, meio, " +
                "valorNoAlerta, eventoNoAlerta e categoria.")
            val raw = response.candidates.singleOrNull()?.text?.trim()
            if (raw == null || raw.length > 2_000) return BankModelResult.Abstained
            val fields = JSONObject(raw)
            bankModelResult(fields.keys().asSequence().associateWith(fields::get))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JSONException) {
            BankModelResult.Abstained
        } catch (_: Exception) {
            BankModelResult.Unavailable
        }
    }
}
