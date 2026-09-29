package com.daxiaamu.dbdown

import java.io.ByteArrayOutputStream
import java.io.File

/** JPEG + XMP directory + original MP4, following Android Motion Photo 1.0. */
internal object MotionPhoto {
    private val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
    fun jpegMetadata(jpeg: ByteArray, videoSize: Long): ByteArray {
        require(videoSize > 0 && jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1" Camera:MotionPhotoPresentationTimestampUs="-1" Camera:MicroVideo="1" Camera:MicroVideoVersion="1" Camera:MicroVideoOffset="$videoSize"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
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
                val oldXmp = marker == 0xe1 && size >= header.size + 2 &&
                    jpeg.copyOfRange(offset + 4, offset + 4 + header.size).contentEquals(header)
                if(!oldXmp) write(jpeg, offset, size + 2)
                offset += size + 2
            }
        }.toByteArray()
    }
    fun write(jpeg: File, video: File, output: File) {
        output.outputStream().use { out ->
            out.write(jpegMetadata(jpeg.readBytes(), video.length()))
            video.inputStream().use { it.copyTo(out) }
        }
    }
}
