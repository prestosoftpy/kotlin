class MemberInvokeOwner {
    operator fun invoke() {}
}

class Cls {
    fun testImplicitReceiver() {
        <!INAPPLICABLE_CANDIDATE!>nullableExtensionProperty<!>()
    }
}

val Cls.nullableExtensionProperty: MemberInvokeOwner?
    get() = null

val Cls.extensionProperty: MemberInvokeOwner
    get() = TODO()

fun testSafeCall(nullable: Cls?) {
    nullable?.extensionProperty()
}

fun testNullableProperty(notNullable: Cls) {
    notNullable.<!INAPPLICABLE_CANDIDATE!>nullableExtensionProperty<!>()
}
