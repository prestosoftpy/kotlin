class MemberInvokeOwner {
    operator fun invoke() {}
}

class Cls {
    fun testImplicitReceiver() {
        <!UNSAFE_IMPLICIT_INVOKE_CALL!>nullableExtensionProperty<!>()
    }
}

val Cls.nullableExtensionProperty: MemberInvokeOwner?
    get() = null

val Cls.extensionProperty: MemberInvokeOwner
    get() = TODO()

fun testSafeCall(nullable: Cls?) {
    nullable?.<!UNSAFE_IMPLICIT_INVOKE_CALL!>extensionProperty<!>()
}

fun testNullableProperty(notNullable: Cls) {
    notNullable.<!UNSAFE_IMPLICIT_INVOKE_CALL!>nullableExtensionProperty<!>()
}
