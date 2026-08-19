package workspace.projectb

class SharedLibrary {
    fun sharedFunction(): String = "shared"

    companion object {
        fun discover(): SharedLibrary = SharedLibrary()
    }
}
