package dev.video.sandbox.video

fun String.makeMP4Filename() = "${this}.${VideoProcessorUtil.DEFAULT_VIDEO_EXTENSION}"