package za.ac.ukzn.ipsnavigation

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import za.ac.ukzn.ipsnavigation.NavigationActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immediately forward to NavigationActivity
        val intent = Intent(this, NavigationActivity::class.java)
        startActivity(intent)
        // Finish this activity so the user cannot navigate back to the blank screen
        finish()
    }
}