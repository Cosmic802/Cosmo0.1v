package com.cosmic802.cosmo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Read-only contact lookup. Contacts never leave the phone. */
object Contacts {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun phones(ctx: Context, query: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!hasPermission(ctx) || query.isBlank()) return out
        try {
            val c = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${query.trim()}%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ) ?: return out
            c.use {
                while (it.moveToNext() && out.size < 5) {
                    out.add(it.getString(0).orEmpty() to it.getString(1).orEmpty())
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun emails(ctx: Context, query: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (!hasPermission(ctx) || query.isBlank()) return out
        try {
            val c = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
                arrayOf("%${query.trim()}%"),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} ASC"
            ) ?: return out
            c.use {
                while (it.moveToNext() && out.size < 5) {
                    out.add(it.getString(0).orEmpty() to it.getString(1).orEmpty())
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
