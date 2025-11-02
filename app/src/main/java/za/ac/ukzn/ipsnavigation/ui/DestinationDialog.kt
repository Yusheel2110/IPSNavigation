package za.ac.ukzn.ipsnavigation.ui

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class DestinationDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            // Set the dialog title
            builder.setTitle("Select Destination")
                // Specify the list array, the items to be selected by default (null for none),
                // and the listener through which to receive callbacks when items are selected
                .setItems(arrayOf("Destination 1", "Destination 2", "Destination 3")) { dialog, which ->
                    // The 'which' argument contains the index position
                    // of the selected item
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}