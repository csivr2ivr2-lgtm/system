package com.example.utils

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object ContactsResolver {

    /**
     * Resolves a name to a phone number by querying the Android Contacts database,
     * or returns the input itself if it contains a pure phone number.
     */
    fun findContactNumber(context: Context, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        // If the query is already an all-numeric pattern, return as-is
        if (trimmed.matches(Regex("^[0-9+\\-()\\s]{3,}$"))) {
            return trimmed
        }

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            // Let's search contacts where display name resembles the query
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$trimmed%")

            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberCol >= 0) {
                        return it.getString(numberCol)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsResolver", "Error searching contacts for: $trimmed", e)
        }
        return null
    }

    /**
     * Helper to list a few template contacts if permission is granted,
     * so that the UI can give feedback or quick taps.
     */
    fun getSampleContacts(context: Context, limit: Int = 5): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (nameCol >= 0 && numberCol >= 0) {
                        val name = it.getString(nameCol)
                        val num = it.getString(numberCol)
                        if (name.isNotEmpty() && num.isNotEmpty()) {
                            list.add(Pair(name, num))
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactsResolver", "Error loading sample contacts", e)
        }
        return list
    }
}
