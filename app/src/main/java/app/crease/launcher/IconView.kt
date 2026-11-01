package app.crease.launcher

import android.content.Context
import android.widget.ImageView

/** Shows one [DOCK_ICONS] entry as a thin line in a single colour. */
class IconView(context: Context, name: String, color: Int) : ImageView(context) {
    init {
        setImageResource(iconRes(name))
        setColorFilter(color)
        scaleType = ScaleType.FIT_CENTER
    }
}

// An explicit table, not getIdentifier(), so resource shrinking keeps these.
private fun iconRes(name: String) = when (name) {
        "phone" -> R.drawable.dock_phone
        "chat" -> R.drawable.dock_chat
        "mail" -> R.drawable.dock_mail
        "globe" -> R.drawable.dock_globe
        "camera" -> R.drawable.dock_camera
        "photo" -> R.drawable.dock_photo
        "music" -> R.drawable.dock_music
        "headphones" -> R.drawable.dock_headphones
        "play" -> R.drawable.dock_play
        "pin" -> R.drawable.dock_pin
        "calendar" -> R.drawable.dock_calendar
        "clock" -> R.drawable.dock_clock
        "note" -> R.drawable.dock_note
        "book" -> R.drawable.dock_book
        "terminal" -> R.drawable.dock_terminal
        "code" -> R.drawable.dock_code
        "card" -> R.drawable.dock_card
        "bag" -> R.drawable.dock_bag
        "gear" -> R.drawable.dock_gear
        "sun" -> R.drawable.dock_sun
        "heart" -> R.drawable.dock_heart
        "star" -> R.drawable.dock_star
        "bolt" -> R.drawable.dock_bolt
        "home" -> R.drawable.dock_home
        "bell" -> R.drawable.dock_bell
        "user" -> R.drawable.dock_user
        "game" -> R.drawable.dock_game
        "lock" -> R.drawable.dock_lock
        "cloud" -> R.drawable.dock_cloud
        "folder" -> R.drawable.dock_folder
        else -> R.drawable.dock_star
}
