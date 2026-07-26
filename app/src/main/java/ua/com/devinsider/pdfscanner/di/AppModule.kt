package ua.com.devinsider.pdfscanner.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ua.com.devinsider.pdfscanner.data.local.AppDatabase
import ua.com.devinsider.pdfscanner.data.local.AppCreatedFileDao
import ua.com.devinsider.pdfscanner.data.local.BookmarkDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase {
        return Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "pdfscanner_database"
        ).build()
    }

    @Provides
    fun provideBookmarkDao(appDatabase: AppDatabase): BookmarkDao {
        return appDatabase.bookmarkDao()
    }

    @Provides
    fun provideAppCreatedFileDao(appDatabase: AppDatabase): AppCreatedFileDao {
        return appDatabase.appCreatedFileDao()
    }
}
