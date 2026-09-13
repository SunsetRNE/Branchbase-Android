package com.branchbase.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JNI 签名对齐的钉子：`RustBridge` 的 `external fun` 与 `core/src/bridge/jni.rs` 的
 * `Java_com_branchbase_core_RustBridge_*` 必须**逐参数、逐类型**对得上。
 *
 * ## 为什么必须钉在源码层
 *
 * `external fun` 只是一句「我要调一个叫这个名字的原生函数」的声明，
 * **参数表对不上编译期查不出来**：Kotlin 侧编译通过、Rust 侧编译通过、
 * JVM 单测也永远不会加载那个 .so（它是 aarch64-android 的，本机跑不了）。
 * 于是错误一路潜伏到真机上——点下那个按钮才炸 `UnsatisfiedLinkError`，
 * 而且报错只给函数名，不说是第几个参数错了。
 *
 * 加 release 的 `make_latest` 时正好踩在这个形状上：`createRelease` / `updateRelease`
 * 各多一个参数，Kotlin 与 Rust 必须在同一个 commit 里一起改。本文件把两边钉在一起。
 *
 * ## 它比「数个数」多做了什么
 *
 * 参数**个数**相同但**类型**排错（比如 `Int` 写成了 `String`）同样会炸，而且更隐蔽。
 * 所以这里把两侧都规范化成 Kotlin 类型名再逐位比较：
 * `JString<'local>` ↔ `String`、`jint` ↔ `Int`、`jboolean` ↔ `Boolean` …
 */
class JniSignatureTest {

    // 单测工作目录是 app 模块根（见 ThemeContrastTest 的同类约定）
    private val kotlinPath = "src/main/java/com/branchbase/core/RustBridge.kt"
    private val rustPath = "../core/src/bridge/jni.rs"

    /** JNI 固定注入的前两个参数，不参与比较。 */
    private val injected = setOf("JNIEnv<'local>", "JNIEnv", "JClass<'local>", "JClass")

    private val rustToKotlin = mapOf(
        "JString<'local>" to "String",
        "jstring" to "String",
        "jint" to "Int",
        "jlong" to "Long",
        "jboolean" to "Boolean",
        "jfloat" to "Float",
        "jdouble" to "Double",
        "jbyteArray" to "ByteArray",
    )

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到源文件：${f.absolutePath}（单测工作目录应为 app 模块根）", f.exists())
        return f.readText()
    }

    /** `name -> [参数类型…]`（按声明顺序）。 */
    private fun kotlinDecls(): Map<String, List<String>> =
        Regex("""private external fun (\w+)\(([^)]*)\)""")
            .findAll(source(kotlinPath))
            .associate { m ->
                val params = m.groupValues[2].split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.substringAfter(':').trim() }
                m.groupValues[1] to params
            }

    /** `name -> [参数类型…]`，已规范化成 Kotlin 类型名、并剔掉 JNI 注入的两个参数。 */
    private fun rustDecls(): Map<String, List<String>> =
        Regex("""fn Java_com_branchbase_core_RustBridge_(\w+)(?:<'local>)?\(([\s\S]*?)\)\s*->""")
            .findAll(source(rustPath))
            .associate { m ->
                val params = m.groupValues[2].split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && ':' in it }
                    .map { it.substringAfter(':').trim() }
                    .filterNot { it in injected }
                    .map { rustToKotlin[it] ?: it }
                m.groupValues[1] to params
            }

    @Test
    fun `Kotlin 声明的原生函数与 Rust 实现一一对应`() {
        val kt = kotlinDecls()
        val rs = rustDecls()
        assertTrue("Kotlin 侧一个 native 声明都没解析到，正则或路径失效了", kt.isNotEmpty())

        val missingInRust = (kt.keys - rs.keys).sorted()
        val missingInKotlin = (rs.keys - kt.keys).sorted()
        assertEquals("这些 external fun 在 core/src/bridge/jni.rs 里没有对应实现：$missingInRust", emptyList<String>(), missingInRust)
        assertEquals("这些 Rust JNI 函数在 RustBridge.kt 里没有声明（改了名或忘了接线）：$missingInKotlin", emptyList<String>(), missingInKotlin)
    }

    @Test
    fun `每个原生函数的参数表逐位一致`() {
        val kt = kotlinDecls()
        val rs = rustDecls()
        assertTrue("Kotlin 侧一个 native 声明都没解析到，正则或路径失效了", kt.isNotEmpty())

        val mismatched = kt.filter { (name, ktParams) ->
            val rsParams = rs[name] ?: return@filter false
            rsParams != ktParams
        }.map { (name, ktParams) ->
            "$name\n     Kotlin: $ktParams\n     Rust  : ${rs[name]}"
        }
        assertEquals(
            "以下原生函数的参数表对不上 —— 编译期查不出来，真机调用时才炸 UnsatisfiedLinkError：\n" +
                mismatched.joinToString("\n"),
            emptyList<String>(),
            mismatched,
        )
    }

    @Test
    fun `参数类型都认得出来（没有未映射的 JNI 类型）`() {
        // 新增一个用别的基础类型（比如 jobject）的原生函数时，这条会红 ——
        // 提醒去上面 rustToKotlin 里补映射，否则「逐位一致」那条会静默跳过它。
        val unknown = rustDecls().flatMap { (name, types) ->
            types.filter { it !in setOf("String", "Int", "Long", "Boolean", "Float", "Double", "ByteArray") }
                .map { "$name -> $it" }
        }
        assertEquals("出现未映射的 JNI 参数类型：$unknown", emptyList<String>(), unknown)
    }
}
