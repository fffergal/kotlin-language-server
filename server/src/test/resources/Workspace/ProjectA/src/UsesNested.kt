package workspace.projecta

import workspace.projectb.Outer.Inner

fun useNested(): String {
    val nested = Inner()
    return nested.nestedFunction()
}
