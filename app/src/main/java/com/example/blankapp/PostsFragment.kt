package com.example.blankapp

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.blankapp.databinding.FragmentPostsBinding
import kotlinx.coroutines.launch

class PostsFragment : Fragment() {

    private var _binding: FragmentPostsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerPosts.layoutManager = LinearLayoutManager(requireContext())
        
        binding.swipeRefresh.setOnRefreshListener { fetchPosts() }
        
        fetchPosts()
    }

    private fun fetchPosts() {
        binding.progressPosts.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val posts = ApiClient.getPosts()
            binding.progressPosts.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            
            if (posts.isNotEmpty()) {
                binding.recyclerPosts.adapter = PostsAdapter(posts)
            } else {
                Toast.makeText(requireContext(), "No posts found or network error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class PostsAdapter(val items: List<PostItem>) : RecyclerView.Adapter<PostsAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(android.R.id.text1)
            val pathText: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.titleText.text = item.name
            holder.pathText.text = item.path
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
