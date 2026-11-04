package ai.familyroute.demo.model

import androidx.compose.ui.graphics.Color

/**
 * 가족 구성원. 색만으로 구분하지 않도록 화면에는 항상 [initial]을 함께 표시한다.
 * [onColor]는 색 원 위 글자색. [id]는 이벤트 직렬화(나중에 동승자 폰)에 쓰는 값.
 */
enum class FamilyMember(
    val id: String,
    val displayName: String,
    val initial: String,
    val color: Color,
    val onColor: Color,
    val isDriver: Boolean = false,
) {
    DAD("dad", "아빠", "아", Color(0xFF3D8BFF), Color(0xFFFFFFFF), isDriver = true),
    MOM("mom", "엄마", "엄", Color(0xFFFF9F43), Color(0xFF0B1220)),
    SEOYEON("seoyeon", "서연", "서", Color(0xFF2DD4BF), Color(0xFF0B1220)),
    MINJUN("minjun", "민준", "민", Color(0xFFFACC15), Color(0xFF0B1220));

    /** "서연이", "엄마가" 처럼 주어 조사를 붙인 이름 */
    val subject: String
        get() {
            val last = displayName.last()
            val hasBatchim = last in '가'..'힣' && (last - '가') % 28 != 0
            return if (hasBatchim) "${displayName}이" else "${displayName}가"
        }

    companion object {
        fun byId(id: String): FamilyMember? = entries.firstOrNull { it.id == id }
        val passengers: List<FamilyMember> get() = entries.filterNot { it.isDriver }
    }
}
