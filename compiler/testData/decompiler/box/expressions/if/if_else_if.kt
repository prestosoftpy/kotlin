fun foo() = 42

fun box(): String {
    val f = foo()
    if (f == 40) {
        return "FAIL"
    } else if (f == 41) {
        return "FAIL"
    } else {
        return "OK"
    }
}