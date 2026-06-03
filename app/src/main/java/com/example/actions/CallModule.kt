package com.example.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import com.example.utils.ContactsResolver

object CallModule {

    /**
     * Executes placing a call/dial action.
     * Starts call directly if permission is granted, otherwise shows the dialer.
     * Returns a string describing the outcome, which the brain can read aloud.
     */
    fun call(nameOrNumber: String, context: Context): String {
        val resolved = ContactsResolver.findContactNumber(context, nameOrNumber)
        val targetNumber = resolved ?: nameOrNumber
        
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intentAction = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(intentAction).apply {
            data = Uri.parse("tel:$targetNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(intent)
            if (resolved != null) {
                if (hasCallPermission) "מחייג ישירות אל השם $nameOrNumber" else "פותח חיוג עבור השם $nameOrNumber"
            } else {
                if (hasCallPermission) "מחייג ישירות אל המספר $nameOrNumber" else "פותח חיוג עבור המספר $nameOrNumber"
            }
        } catch (e: Exception) {
            // Ultimate fallback to ACTION_DIAL (which never requires permissions)
            if (intentAction != Intent.ACTION_DIAL) {
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$targetNumber")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                    "פותח מסך חיוג עבור $nameOrNumber"
                } catch (ex: Exception) {
                    "לא ניתן לבצע שיחה בדוחק"
                }
            } else {
                "לא ניתן לבצע שיחה"
            }
        }
    }
}
