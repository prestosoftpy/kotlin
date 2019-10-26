fun fail(message: String): Nothing {
    throw IllegalArgumentException(message)
}

fun box(): String {
    try {
        fail("Testing fail")
        return "FAIL"
    } catch (e: IllegalArgumentException) {
        return "OK"
    } finally {
        return "OK"
    }
}