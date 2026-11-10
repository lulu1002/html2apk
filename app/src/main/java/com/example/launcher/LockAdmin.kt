package com.example.launcher

import android.app.admin.DeviceAdminReceiver

/** Só serve para o gesto "Bloquear a tela" (política force-lock). Opcional e removível. */
class LockAdmin : DeviceAdminReceiver()
