package dev.nathanmkaya.patchkit.sample

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform