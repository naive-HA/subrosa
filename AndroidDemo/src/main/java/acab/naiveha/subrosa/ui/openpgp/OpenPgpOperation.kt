package acab.naiveha.subrosa.ui.openpgp

enum class OpenPgpOperation {
    NONE,
    PREFLIGHT,
    SAVE,
    READ,
    WIPE,
    CHANGE_ADMIN_PIN,
    RESET_ADMIN_PIN,
    CHANGE_USER_PIN,
    RESET_USER_PIN,
}
