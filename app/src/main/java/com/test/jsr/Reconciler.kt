package com.test.jsr

import org.json.JSONObject

/**
 * Операции патча (план 1.5 A2 / issue #2): diff старого и нового дерева
 * даёт минимальные мутации вместо полной пересборки. Идентичность узла:
 * сначала prop `key`, затем тот же тип на той же позиции.
 */
sealed class PatchOp {
    /** Новая запись узла для существующего view (view пере-тегируется). */
    data class UpdateProps(val nodeId: String, val node: JsNode) : PatchOp()

    /** Вставка нового поддерева в родителя на позицию [index]. */
    data class InsertChild(val parentId: String, val index: Int, val node: JsNode) : PatchOp()

    /** Удаление существующего ребёнка [nodeId] у родителя [parentId]. */
    data class RemoveChild(val parentId: String, val nodeId: String) : PatchOp()

    /** Перестановка существующего view на новую позицию (view сохраняется). */
    data class MoveChild(val parentId: String, val nodeId: String, val toIndex: Int, val node: JsNode) : PatchOp()

    /** Смена типа на позиции: поддерево пересобирается целиком. */
    data class ReplaceSubtree(val nodeId: String, val node: JsNode) : PatchOp()
}

object Reconciler {

    /** ops: мутации; renames: пары (старая запись, новая запись) для пере-тегирования. */
    data class DiffResult(val ops: List<PatchOp>, val renames: List<Pair<JsNode, JsNode>>)

    /** Корни считаются одной и той же позицией (движок один, mount один). */
    fun diff(old: JsNode, new: JsNode): DiffResult {
        val ops = mutableListOf<PatchOp>()
        val renames = mutableListOf<Pair<JsNode, JsNode>>()
        diffNode(old, new, ops, renames)
        return DiffResult(ops, renames)
    }

    /** Пара с совпадающей идентичностью: props + рекурсия в children. */
    private fun diffNode(
        old: JsNode,
        new: JsNode,
        ops: MutableList<PatchOp>,
        renames: MutableList<Pair<JsNode, JsNode>>,
    ) {
        if (old.type != new.type) {
            ops += PatchOp.ReplaceSubtree(old.nodeId, new)
            return
        }
        if (old.nodeId != new.nodeId) renames += old to new
        if (propsDiffer(old.props, new.props)) ops += PatchOp.UpdateProps(old.nodeId, new)
        diffChildren(old.nodeId, old.children, new.children, ops, renames)
    }

    private fun diffChildren(
        parentId: String,
        oldList: List<JsNode>,
        newList: List<JsNode>,
        ops: MutableList<PatchOp>,
        renames: MutableList<Pair<JsNode, JsNode>>,
    ) {
        val oldByKey = HashMap<String, Int>()
        oldList.forEachIndexed { i, child -> child.key?.let { oldByKey[it] = i } }
        val matched = BooleanArray(oldList.size)
        val newMatch = arrayOfNulls<Int>(newList.size) // new index -> old index

        newList.forEachIndexed { i, newChild ->
            val oldIndex = matchOf(oldList, oldByKey, matched, newChild, i)
            if (oldIndex != null) {
                matched[oldIndex] = true
                newMatch[i] = oldIndex
                diffNode(oldList[oldIndex], newChild, ops, renames)
            } else {
                ops += PatchOp.InsertChild(parentId, i, newChild)
            }
        }
        oldList.forEachIndexed { i, oldChild ->
            if (!matched[i]) ops += PatchOp.RemoveChild(parentId, oldChild.nodeId)
        }
        // Ключевые совпадения со сменой позиции — moveChild (view сохраняется).
        newList.forEachIndexed { i, newChild ->
            val oldIndex = newMatch[i]
            if (oldIndex != null && newChild.key != null && oldIndex != i) {
                ops += PatchOp.MoveChild(parentId, oldList[oldIndex].nodeId, i, newChild)
            }
        }
    }

    /** key сначала; иначе тот же тип на той же позиции (если ещё не занят). */
    private fun matchOf(
        oldList: List<JsNode>,
        oldByKey: Map<String, Int>,
        matched: BooleanArray,
        newChild: JsNode,
        position: Int,
    ): Int? {
        newChild.key?.let { key ->
            val byKey = oldByKey[key]
            if (byKey != null && !matched[byKey]) return byKey
        }
        val atPosition = oldList.getOrNull(position)
        if (atPosition != null && !matched[position]) {
            return position // та же позиция: diffNode решит update/replace по типу
        }
        return null
    }

    /** Глубокое сравнение props по ключам и значениям (порядок не важен). */
    fun propsDiffer(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return true
        a.keys().forEach { k ->
            if (!b.has(k)) return true
            if (!valueEquals(a.get(k), b.get(k))) return true
        }
        return false
    }

    private fun valueEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> !propsDiffer(a, b)
        a is org.json.JSONArray && b is org.json.JSONArray -> jsonArraysEqual(a, b)
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun jsonArraysEqual(a: org.json.JSONArray, b: org.json.JSONArray): Boolean {
        if (a.length() != b.length()) return false
        (0 until a.length()).map { a.get(it) }.forEachIndexed { i, v ->
            if (!valueEquals(v, b.get(i))) return false
        }
        return true
    }
}
