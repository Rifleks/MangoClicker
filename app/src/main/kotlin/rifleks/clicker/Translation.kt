package rifleks.clicker

import android.content.Context
import org.json.JSONObject
import java.util.*

object Translation {
    private var translations: JSONObject? = null
    var currentLanguage: String = "en" // Язык по умолчанию

    fun loadTranslations(context: Context) {
        try {
            val inputStream = context.assets.open("translations.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val jsonString = String(buffer, Charsets.UTF_8)
            translations = JSONObject(jsonString)

            val availableLanguages = translations?.keys()?.asSequence()?.toList() ?: listOf("en")

            val systemLanguage = Locale.getDefault().language

            if (availableLanguages.contains(systemLanguage)) {
                currentLanguage = systemLanguage
            } else {
                if (availableLanguages.contains("en")) {
                    currentLanguage = "en" // Используем английский как запасной вариант
                } else {
                    currentLanguage = availableLanguages.first()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun translate(key: String): String {
        return try {
            translations?.getJSONObject(currentLanguage)?.optString(key) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}