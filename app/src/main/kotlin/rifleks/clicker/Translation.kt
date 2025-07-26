package rifleks.clicker

import android.content.Context
import org.json.JSONObject
import java.util.*

object Translation {
    private var translations: JSONObject? = null
    var currentLanguage: String = "en" // Язык по умолчанию
        set(value) {
            field = value
            // Log.d("Translation", "Language set to: $value") // Remove
        }

    fun loadTranslations(context: Context) {
        try {
            val inputStream = context.assets.open("translations.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val jsonString = String(buffer, Charsets.UTF_8)
            translations = JSONObject(jsonString)

            // Получаем список доступных языков из translations.json
            val availableLanguages = translations?.keys()?.asSequence()?.toList() ?: listOf("en")

            // Определяем язык системы
            val systemLanguage = Locale.getDefault().language

            // Проверяем, есть ли точное соответствие языка системы в переводах
            if (availableLanguages.contains(systemLanguage)) {
                currentLanguage = systemLanguage
            } else {
                // Если точного соответствия нет, проверяем, есть ли язык по умолчанию (en)
                if (availableLanguages.contains("en")) {
                    currentLanguage = "en" // Используем английский как запасной вариант
                } else {
                    // Если английского нет, берем первый доступный язык.
                    currentLanguage = availableLanguages.first()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun translate(key: String): String {
        return try {
            translations?.getJSONObject(currentLanguage)?.getString(key) ?: key
        } catch (e: Exception) {
            key
        }
    }
}