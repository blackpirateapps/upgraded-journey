package com.example.blankapp

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.blankapp.data.PendingPost
import com.example.blankapp.data.QueueStore
import com.example.blankapp.databinding.FragmentQueueBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerQueue.layoutManager = LinearLayoutManager(requireContext())

        val store = QueueStore.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            store.getAll().collectLatest { posts ->
                if (posts.isEmpty()) {
                    binding.textEmptyQueue.visibility = View.VISIBLE
                    binding.recyclerQueue.visibility = View.GONE
                } else {
                    binding.textEmptyQueue.visibility = View.GONE
                    binding.recyclerQueue.visibility = View.VISIBLE
                    binding.recyclerQueue.adapter = QueueAdapter(posts)
                }
            }
        }
    }

    private inner class QueueAdapter(val items: List<PendingPost>) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.titleText.text = if (item.title.isNotBlank()) item.title else "Untitled Post"
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
