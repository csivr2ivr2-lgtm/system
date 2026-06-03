package com.example.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.utils.ContactsResolver

object WhatsAppModule {

    /**
     * Tries to open a WhatsApp conversation with the target name/number.
     * Prepares Hebrew feedback string for TTS.
     */
    fun send(nameOrNumber: String, context: Context): String {
        val resolved = ContactsResolver.findContactNumber(context, nameOrNumber)
        
        return if (resolved != null) {
            val formattedNumber = formatToInternational(resolved)
            val uri = Uri.parse("whatsapp://send?phone=$formattedNumber")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
                "פותח שיחת וואטסאפ עם $nameOrNumber"
            } catch (e: Exception) {
                // Fallback to WhatsApp Business
                try {
                    val w4bIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp.w4b")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(w4bIntent)
                    "פותח וואטסאפ עסקי עם $nameOrNumber"
                } catch (ex: Exception) {
                    // General web deep-link fallback
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$formattedNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallbackIntent)
                    "פותח קישור פנימי לוואטסאפ עבור $nameOrNumber"
                }
            }
        } else {
            // General WhatsApp launch with numeric detection
            val numericOnly = nameOrNumber.replace(Regex("[^0-9]"), "")
            if (numericOnly.length >= 7) {
                val formattedNumber = formatToInternational(numericOnly)
                val uri = Uri.parse("whatsapp://send?phone=$formattedNumber")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                    "פותח וואטסאפ למספר $nameOrNumber"
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$formattedNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallbackIntent)
                    "פותח קישור פנימי לוואטסאפ למספר $nameOrNumber"
                }
            } else {
                // Just launch the WhatsApp App by package
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    "פותח את אפליקציית וואטסאפ"
                } else {
                    val uri = Uri.parse("https://wa.me/?text=${Uri.encode(nameOrNumber)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "פותח וואטסאפ עם הטקסט $nameOrNumber"
                }
            }
        }
    }

    private fun formatToInternational(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        return if (digits.startsWith("0")) {
            "972" + digits.substring(1)
        } else if (digits.startsWith("972")) {
            digits
        } else {
            digits
        }
    }
}
