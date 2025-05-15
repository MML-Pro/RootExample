package com.example.rootexample

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.rootexample.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top + 32, systemBars.right, systemBars.bottom)
            insets
        }


        binding.changeMacButton.setOnClickListener {
            // استخدام Coroutine لتنفيذ أمر تغيير عنوان MAC
            CoroutineScope(Dispatchers.Main).launch {
                val newMacAddress = "00:11:22:33:33:55" // عنوان MAC جديد (مثال)
                val result = changeMacAddress("wlan0", newMacAddress)
                binding.statusText.text = result
            }
        }
    }

    // دالة للتحقق من وجود صلاحيات الروت
    private suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            process.waitFor()
            return@withContext process.exitValue() == 0
        } catch (_: Exception) {
            return@withContext false
        }
    }

    // دالة لتنفيذ أوامر الروت

    private suspend fun executeRootCommand(command: String): String = withContext(Dispatchers.IO) {
//    val TAG = "ExecuteRootCommand"
        var outputStream: DataOutputStream? = null
        var stdoutReader: BufferedReader? = null
        var stderrReader: BufferedReader? = null

        val output = StringBuilder()
        try {
            Log.d(TAG, "Executing command: $command")
            val process = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(process.outputStream)
            stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            // إرسال الأوامر
            outputStream.writeBytes("$command\n")
            outputStream.writeBytes("exit\n")
            outputStream.flush()

            // قراءة الإخراج (stdout)
            var line: String? = stdoutReader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = stdoutReader.readLine()
            }

            // قراءة الأخطاء (stderr)
            line = stderrReader.readLine()
            while (line != null) {
                output.append("STDERR: ").append(line).append("\n")
                line = stderrReader.readLine()
            }

            process.waitFor()
            val exitValue = process.exitValue()
            Log.d(TAG, "Command exit value: $exitValue, Output: $output")
            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing command: ${e.message}", e)
            output.append("Exception: ${e.message}")
            output.toString().trim()
        } finally {
            outputStream?.close()
            stdoutReader?.close()
            stderrReader?.close()
        }
    }

    // دالة لتغيير عنوان MAC

   private suspend fun changeMacAddress(interfaceName: String, newMac: String): String {
//    val TAG = "ChangeMacAddress"

    // تسجيل الإدخال
    Log.d(TAG, "Starting MAC address change. Interface: $interfaceName, New MAC: $newMac")

    // التحقق من صلاحيات الروت
    if (!isRootAvailable()) {
        Log.e(TAG, "Root access not available")
        return "Root access not available."
    }
    Log.d(TAG, "Root access confirmed")

    // التحقق من الواجهات المتاحة
    val interfacesCommand = "ip link show"
    Log.d(TAG, "Checking available interfaces: $interfacesCommand")
    val interfacesResult = try {
        executeRootCommand(interfacesCommand)
    } catch (e: Exception) {
        Log.e(TAG, "Exception checking interfaces: ${e.message}", e)
        return "Failed to check interfaces: ${e.message}"
    }
    Log.d(TAG, "Interfaces output: $interfacesResult")
    if (!interfacesResult.contains(interfaceName)) {
        Log.e(TAG, "Interface $interfaceName not found")
        return "Interface $interfaceName not found. Available interfaces: $interfacesResult"
    }

    // التحقق من حالة الواجهة قبل التغيير
    val initialStateCommand = "ip link show $interfaceName"
    Log.d(TAG, "Checking initial interface state: $initialStateCommand")
    val initialState = try {
        executeRootCommand(initialStateCommand)
    } catch (e: Exception) {
        Log.e(TAG, "Exception checking initial state: ${e.message}", e)
        return "Failed to check initial state: ${e.message}"
    }
    Log.d(TAG, "Initial interface state: $initialState")

    // تنفيذ أوامر تغيير MAC بشكل منفصل
    val commands = listOf(
        "ip link set $interfaceName down",
        "ip link set $interfaceName address $newMac",
        "ip link set $interfaceName up"
    )
    var changeResult = ""
    for (cmd in commands) {
        Log.d(TAG, "Executing command: $cmd")
        val result = try {
            executeRootCommand(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing command: ${e.message}", e)
            return "Failed to execute command '$cmd': ${e.message}"
        }
        Log.d(TAG, "Command output: $result")
        changeResult += "$cmd: $result\n"
        if (result.contains("Error", ignoreCase = true) ||
            result.contains("cannot", ignoreCase = true) ||
            result.contains("not permitted", ignoreCase = true)
        ) {
            Log.e(TAG, "Error in command '$cmd'. Output: $result")
            return "Failed to change MAC address: $result"
        }
        // تأخير بسيط بين الأوامر (اختياري)
        delay(500)
    }

    // إعادة تشغيل خدمة الشبكة (Wi-Fi) لضمان الاتصال
    Log.d(TAG, "Restarting network service to reconnect...")
    val restartNetworkCommand = if (interfaceName.contains("wlan")) {
        // لإعادة تشغيل خدمة الـ Wi-Fi
        "svc wifi disable && svc wifi enable"
    } else {
        // لأنواع واجهات أخرى (اختياري، يمكنك تعديله حسب الحاجة)
        "svc data disable && svc data enable"
    }
    try {
        executeRootCommand(restartNetworkCommand)
        Log.d(TAG, "Network service restarted successfully")
        // تأخير بسيط للسماح للشبكة بإعادة الاتصال
        delay(2000)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to restart network service: ${e.message}", e)
        return "MAC address changed, but failed to restart network: ${e.message}"
    }

    // أمر استعلام لجلب عنوان MAC الحالي
    val queryCommand = "ip link show $interfaceName"
    Log.d(TAG, "Executing query command: $queryCommand")
    val queryResult = try {
        executeRootCommand(queryCommand)
    } catch (e: Exception) {
        Log.e(TAG, "Exception during query execution: ${e.message}", e)
        return "Failed to query MAC address: ${e.message}"
    }
    Log.d(TAG, "Query command output: $queryResult")

    // استخراج عنوان MAC باستخدام regex
    val macRegex = Regex("link/ether ([0-9a-fA-F:]{17})")
    val match = macRegex.find(queryResult)
    val currentMac = match?.groups?.get(1)?.value

    // تسجيل نتيجة الاستعلام
    return if (currentMac != null) {
        Log.d(TAG, "MAC address changed successfully.\n\nCurrent MAC: $currentMac")
        "MAC address changed successfully.\n\nCurrent MAC: $currentMac"
    } else {
        Log.e(TAG, "Failed to retrieve current MAC.\n\nQuery output: $queryResult")
        "MAC address change attempted, but failed to retrieve current MAC:\n\n$queryResult"
    }
}
}




