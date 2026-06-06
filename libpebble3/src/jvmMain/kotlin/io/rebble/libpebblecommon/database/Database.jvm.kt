package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import java.io.File

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    val dir = File(System.getProperty("user.home"), ".config/stoandl")
    dir.mkdirs()
    val dbFile = File(dir, DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.absolutePath,
    )
}