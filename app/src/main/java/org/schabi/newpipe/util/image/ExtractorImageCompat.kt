package org.schabi.newpipe.util.image

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.player.playqueue.PlayQueueItem

/**
 * Strongly typed adapters for the image APIs exposed by the pinned PipePipeExtractor version.
 */
object ExtractorImageCompat {
    @JvmStatic
    fun thumbnailImages(item: InfoItem?): List<Image> = singleUrlImage(item?.thumbnailUrl)

    @JvmStatic
    fun thumbnailImages(info: StreamInfo?): List<Image> = info?.thumbnails.orEmpty()
        .ifEmpty { singleUrlImage(info?.thumbnailUrl) }

    @JvmStatic
    fun thumbnailImages(info: PlaylistInfo?): List<Image> = singleUrlImage(info?.thumbnailUrl)

    @JvmStatic
    fun thumbnailImages(item: PlayQueueItem?): List<Image> = item?.thumbnails.orEmpty()

    @JvmStatic
    fun uploaderAvatarImages(item: CommentsInfoItem?): List<Image> = singleUrlImage(item?.uploaderAvatarUrl)

    @JvmStatic
    fun uploaderAvatarImages(info: StreamInfo?): List<Image> = info?.uploaderAvatars.orEmpty()
        .ifEmpty { singleUrlImage(info?.uploaderAvatarUrl) }

    @JvmStatic
    fun uploaderAvatarImages(info: PlaylistInfo?): List<Image> = singleUrlImage(info?.uploaderAvatarUrl)

    @JvmStatic
    fun parentChannelAvatarImages(info: ChannelInfo?): List<Image> = singleUrlImage(info?.parentChannelAvatarUrl)

    @JvmStatic
    fun setThumbnailImages(item: InfoItem, images: List<Image>) {
        item.thumbnailUrl = ImageStrategy.imageListToDbUrl(images)
    }

    private fun singleUrlImage(url: String?): List<Image> = url?.takeIf { it.isNotBlank() }?.let {
        listOf(
            Image(
                it,
                Image.HEIGHT_UNKNOWN,
                Image.WIDTH_UNKNOWN,
                Image.ResolutionLevel.UNKNOWN
            )
        )
    }.orEmpty()
}
