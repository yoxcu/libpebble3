package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.stoandlConfigDir
import java.io.File

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    val dir = stoandlConfigDir()
    dir.mkdirs()
    val dbFile = File(dir, DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.absolutePath,
    )
}