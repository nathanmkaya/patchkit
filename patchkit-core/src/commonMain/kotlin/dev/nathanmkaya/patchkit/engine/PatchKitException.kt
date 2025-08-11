package dev.nathanmkaya.patchkit.engine

open class PatchKitException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PreconditionFailedException(
    message: String,
) : PatchKitException(message)

class PostconditionFailedException(
    message: String,
) : PatchKitException(message)
