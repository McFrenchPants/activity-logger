package com.mcfrenchpants.activityledger.core.data.db.dao

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Minimal JVM class-file reader that extracts class- and method-level
 * annotations, INCLUDING those with CLASS retention.
 *
 * Why: Room's @Dao, @Query, @Insert, @Update, @Upsert, @Delete and @RawQuery are
 * all declared with CLASS retention, so java.lang.reflect cannot see them at test
 * runtime. They are still recorded in the compiled class file as
 * RuntimeInvisibleAnnotations, which is what this reads. No ASM dependency is
 * needed (none is on the test compile classpath).
 */
internal object ClassFileAnnotations {

    /** An annotation: its JVM type descriptor (e.g. "Landroidx/room/Query;") and element values. */
    data class Annotation(val descriptor: String, val values: Map<String, Any?>)

    /**
     * A method: [descriptor] is the erased JVM descriptor; [signature] is the generic
     * signature (e.g. with `List<...>` type arguments) when the compiler emitted one.
     */
    data class Method(
        val name: String,
        val descriptor: String,
        val annotations: List<Annotation>,
        val signature: String? = null,
    )

    data class ClassInfo(val annotations: List<Annotation>, val methods: List<Method>)

    fun read(type: Class<*>): ClassInfo {
        val resource = "/" + type.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(type.getResourceAsStream(resource)) { "class file not found: $resource" }
            .use { it.readBytes() }
        return parse(DataInputStream(ByteArrayInputStream(bytes)))
    }

    private fun parse(input: DataInputStream): ClassInfo {
        check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort() // minor
        input.readUnsignedShort() // major
        val pool = readConstantPool(input)
        input.readUnsignedShort() // access flags
        input.readUnsignedShort() // this_class
        input.readUnsignedShort() // super_class
        repeat(input.readUnsignedShort()) { input.readUnsignedShort() } // interfaces
        repeat(input.readUnsignedShort()) { // fields
            input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
            readAttributes(input, pool)
        }
        val methods = List(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val name = pool[input.readUnsignedShort()] as String
            val descriptor = pool[input.readUnsignedShort()] as String
            val attributes = readAttributes(input, pool)
            Method(name, descriptor, attributes.annotations, attributes.signature)
        }
        val classAnnotations = readAttributes(input, pool).annotations
        return ClassInfo(classAnnotations, methods)
    }

    private fun readConstantPool(input: DataInputStream): Array<Any?> {
        val count = input.readUnsignedShort()
        val pool = arrayOfNulls<Any?>(count)
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> pool[i] = input.readUTF()
                3 -> pool[i] = input.readInt()
                4 -> pool[i] = input.readFloat()
                5 -> { pool[i] = input.readLong(); i++ }
                6 -> { pool[i] = input.readDouble(); i++ }
                7, 8, 16, 19, 20 -> input.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> error("unsupported constant pool tag $tag")
            }
            i++
        }
        return pool
    }

    private class Attributes(val annotations: List<Annotation>, val signature: String?)

    /** Reads an attribute table: the annotations found in it (visible and invisible) and any Signature. */
    private fun readAttributes(input: DataInputStream, pool: Array<Any?>): Attributes {
        val result = mutableListOf<Annotation>()
        var signature: String? = null
        repeat(input.readUnsignedShort()) {
            val name = pool[input.readUnsignedShort()] as String
            val length = input.readInt()
            when (name) {
                "RuntimeInvisibleAnnotations", "RuntimeVisibleAnnotations" ->
                    repeat(input.readUnsignedShort()) { result += readAnnotation(input, pool) }
                "Signature" -> signature = pool[input.readUnsignedShort()] as String
                else -> input.readFully(ByteArray(length))
            }
        }
        return Attributes(result, signature)
    }

    private fun readAnnotation(input: DataInputStream, pool: Array<Any?>): Annotation {
        val descriptor = pool[input.readUnsignedShort()] as String
        val values = LinkedHashMap<String, Any?>()
        repeat(input.readUnsignedShort()) {
            val name = pool[input.readUnsignedShort()] as String
            values[name] = readElementValue(input, pool)
        }
        return Annotation(descriptor, values)
    }

    private fun readElementValue(input: DataInputStream, pool: Array<Any?>): Any? =
        when (val tag = input.readUnsignedByte().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> pool[input.readUnsignedShort()]
            'e' -> {
                val enumType = pool[input.readUnsignedShort()] as String
                enumType + "." + pool[input.readUnsignedShort()] as String
            }
            'c' -> pool[input.readUnsignedShort()]
            '@' -> readAnnotation(input, pool)
            '[' -> List(input.readUnsignedShort()) { readElementValue(input, pool) }
            else -> error("unsupported element value tag $tag")
        }
}
