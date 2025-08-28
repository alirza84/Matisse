package github.leavesczy.matisse.internal.logic

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import github.leavesczy.matisse.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @Author: leavesCZY
 * @Date: 2022/6/2 11:11
 * @Desc:
 */

internal object MediaProvider {

    data class MediaInfo(
        val mediaId: Long,
        val bucketId: String,
        val bucketName: String,
        val uri: Uri,
        val path: String,
        val name: String,
        val mimeType: String
    )

    // --- Create image ---
    suspend fun createImage(
        context: Context,
        imageName: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, imageName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            }
            val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            context.contentResolver.insert(imageCollection, contentValues)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    // --- Delete media ---
    suspend fun deleteMedia(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    // --- Load resources progressively (paged with Flow) ---
    fun loadResources(
        context: Context,
        mediaType: MediaType,
        pageSize: Int = 100
    ): Flow<List<MediaInfo>> = flow {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val uri = MediaStore.Files.getContentUri("external")
        val selection = generateSqlSelection(mediaType)

        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val batch = mutableListOf<MediaInfo>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))

                if (path.isNullOrBlank() || size <= 0) continue
                val file = File(path)
                if (!file.isFile || !file.exists()) continue

                val uriItem = ContentUris.withAppendedId(uri, id)
                batch.add(
                    MediaInfo(
                        mediaId = id,
                        bucketId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)) ?: "",
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)) ?: "",
                        path = path,
                        uri = uriItem,
                        name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                    )
                )

                if (batch.size >= pageSize) {
                    emit(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) emit(batch)
        }
    }.flowOn(Dispatchers.IO) // ✅ move emission context to IO


    // --- Load single resource by Uri ---
    suspend fun loadResources(context: Context, uri: Uri): MediaInfo? =
        withContext(Dispatchers.IO) {
            val id = ContentUris.parseId(uri)
            val selection = "${MediaStore.MediaColumns._ID} = $id"
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            )
            val contentUri = MediaStore.Files.getContentUri("external")

            context.contentResolver.query(contentUri, projection, selection, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val mediaId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    val uriItem = ContentUris.withAppendedId(contentUri, mediaId)
                    return@withContext MediaInfo(
                        mediaId = mediaId,
                        bucketId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)) ?: "",
                        bucketName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)) ?: "",
                        path = path,
                        uri = uriItem,
                        name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                    )
                }
            }
            null
        }

    // --- Selection helper ---
    private fun generateSqlSelection(mediaType: MediaType): String {
        val mediaTypeColumn = MediaStore.Files.FileColumns.MEDIA_TYPE
        val mediaTypeImageColumn = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val mediaTypeVideoColumn = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mimeTypeColumn = MediaStore.Files.FileColumns.MIME_TYPE
        val queryImageSelection =
            "$mediaTypeColumn = $mediaTypeImageColumn and $mimeTypeColumn like 'image/%'"
        val queryVideoSelection =
            "$mediaTypeColumn = $mediaTypeVideoColumn and $mimeTypeColumn like 'video/%'"

        return when (mediaType) {
            is MediaType.ImageOnly -> queryImageSelection
            MediaType.VideoOnly -> queryVideoSelection
            is MediaType.ImageAndVideo -> "$queryImageSelection or $queryVideoSelection"
            is MediaType.MultipleMimeType -> mediaType.mimeTypes.joinToString(
                prefix = "$mimeTypeColumn in (",
                postfix = ")",
                separator = ","
            ) { "'$it'" }
        }
    }
}