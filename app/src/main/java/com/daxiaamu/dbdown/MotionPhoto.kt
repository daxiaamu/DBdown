package com.daxiaamu.dbdown

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JPEG + XMP directory + original MP4, following Android Motion Photo 1.0. */
internal object MotionPhoto {
    private val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
    fun jpegMetadata(jpeg: ByteArray, videoSize: Long): ByteArray {
        require(videoSize > 0 && jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/" OpCamera:MotionPhotoOwner="oplus" OpCamera:OLivePhotoVersion="2" OpCamera:MotionPhotoFeatureFlag="1" OpCamera:VideoLength="$videoSize" OpCamera:MotionPhotoPrimaryPresentationTimestampUs="0" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="0"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val payload = header + xml.toByteArray(Charsets.UTF_8)
        val length = payload.size + 2
        return ByteArrayOutputStream().apply {
            write(jpeg, 0, 2)
            write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()))
            write(payload)
            var offset = 2
            while(offset < jpeg.size) {
                require(jpeg[offset] == 0xff.toByte() && offset + 1 < jpeg.size)
                val marker = jpeg[offset + 1].toInt() and 255
                if(marker == 0xda || marker == 0xd9) { write(jpeg, offset, jpeg.size - offset); break }
                require(offset + 3 < jpeg.size)
                val size = ((jpeg[offset + 2].toInt() and 255) shl 8) + (jpeg[offset + 3].toInt() and 255)
                require(size >= 2 && offset + size + 2 <= jpeg.size)
                val oldMpf = marker == 0xe2 && size >= 6 &&
                    jpeg.copyOfRange(offset + 4, offset + 8).contentEquals(byteArrayOf(77, 80, 70, 0))
                val oldXmp = marker == 0xe1 && size >= header.size + 2 &&
                    jpeg.copyOfRange(offset + 4, offset + 4 + header.size).contentEquals(header)
                if(!oldXmp && !oldMpf) write(jpeg, offset, size + 2)
                offset += size + 2
            }
        }.toByteArray().let { photo ->
            val mpf = primaryMpf(photo.size + 74)
            photo.copyOfRange(0, 2) + mpf + photo.copyOfRange(2, photo.size)
        }
    }

    // Single-image MP Index: offsets are relative to its TIFF header; size excludes appended MP4.
    private fun primaryMpf(jpegSize: Int): ByteArray = ByteBuffer.allocate(74).order(ByteOrder.BIG_ENDIAN).apply {
        put(0xff.toByte()); put(0xe2.toByte()); putShort(72)
        put(byteArrayOf(77, 80, 70, 0))
        putShort(0x4d4d); putShort(42); putInt(8)
        putShort(3)
        putShort(0xb000.toShort()); putShort(7); putInt(4); put("0100".toByteArray())
        putShort(0xb001.toShort()); putShort(4); putInt(1); putInt(1)
        putShort(0xb002.toShort()); putShort(7); putInt(16); putInt(50)
        putInt(0)
        putInt(0x00030000); putInt(jpegSize); putInt(0); putShort(0); putShort(0)
    }.array()

    fun write(jpeg: File, video: File, output: File) {
        output.outputStream().use { out ->
            out.write(jpegMetadata(jpeg.readBytes(), video.length()))
            video.inputStream().use { it.copyTo(out) }
        }
    }
}
