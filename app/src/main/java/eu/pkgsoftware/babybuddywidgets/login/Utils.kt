package eu.pkgsoftware.babybuddywidgets.login

class Utils {
    companion object {
        fun padToLen(s: String, c: Char, length: Int): String {
            val sBuilder = StringBuilder(s)
            while (sBuilder.length < length) {
                sBuilder.insert(0, c)
            }
            return sBuilder.toString()
        }
    }
}
