package workspace.projecta

import workspace.projectb.SharedLibrary

fun useSharedLibrary(): String {
    val discovered = SharedLibrary.discover()
    val library = SharedLibrary()
    return library.sharedFunction()
}
