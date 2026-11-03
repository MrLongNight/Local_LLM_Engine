package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

enum class ChatRole(val roleName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    DEVELOPER("developer");

    companion object {
        fun fromString(value: String): ChatRole =
            entries.firstOrNull { it.roleName.equals(value, ignoreCase = true) } ?: USER
    }
}

/**
 * A single conversational turn in a multi-message chat thread.
 *
 * @property role The author of the message (system, user, assistant).
 * @property content The textual message payload.
 * @property timestamp Epoch millisecond timestamp of the message creation.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        role = ChatRole.valueOf(parcel.readString() ?: ChatRole.USER.name),
        content = parcel.readString() ?: "",
        timestamp = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(role.name)
        parcel.writeString(content)
        parcel.writeLong(timestamp)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ChatMessage> = object : Parcelable.Creator<ChatMessage> {
            override fun createFromParcel(parcel: Parcel): ChatMessage = ChatMessage(parcel)
            override fun newArray(size: Int): Array<ChatMessage?> = arrayOfNulls(size)
        }

        fun system(content: String): ChatMessage = ChatMessage(ChatRole.SYSTEM, content)
        fun user(content: String): ChatMessage = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String): ChatMessage = ChatMessage(ChatRole.ASSISTANT, content)
        fun developer(content: String): ChatMessage = ChatMessage(ChatRole.DEVELOPER, content)
    }
}
