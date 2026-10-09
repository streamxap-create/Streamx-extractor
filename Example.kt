package com.streamx.extractor

fun main() {
    val video = StreamxExtractor.getVideo("dQw4w9WgXcQ")
    println("Title: ${video.title}")
    println("Channel: ${video.channelName}")
    println("Duration: ${video.duration}s")
    println("Views: ${video.viewCount}")
    println("Streams: ${video.streams.size}")
}
