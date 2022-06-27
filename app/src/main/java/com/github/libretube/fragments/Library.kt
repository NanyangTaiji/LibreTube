package com.github.libretube.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.libretube.R
import com.github.libretube.adapters.PlaylistsAdapter
import com.github.libretube.dialogs.CreatePlaylistDialog
import com.github.libretube.util.PreferenceHelper
import com.github.libretube.util.RetrofitInstance
import com.google.android.material.floatingactionbutton.FloatingActionButton
import retrofit2.HttpException
import java.io.IOException

class Library : Fragment() {

    private val TAG = "LibraryFragment"
    lateinit var token: String
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var refreshLayout: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        playlistRecyclerView = view.findViewById(R.id.playlist_recView)
        playlistRecyclerView.layoutManager = LinearLayoutManager(view.context)
        token = PreferenceHelper.getToken(requireContext())
        refreshLayout = view.findViewById(R.id.playlist_refresh)
        if (token != "") {
            view.findViewById<ImageView>(R.id.boogh2).visibility = View.GONE
            view.findViewById<TextView>(R.id.textLike2).visibility = View.GONE
            fetchPlaylists()
            refreshLayout.isEnabled = true
            refreshLayout.setOnRefreshListener {
                fetchPlaylists()
            }
            val createPlaylistButton = view.findViewById<FloatingActionButton>(R.id.create_playlist)
            createPlaylistButton.setOnClickListener {
                val newFragment = CreatePlaylistDialog()
                newFragment.show(childFragmentManager, "Create Playlist")
            }
        } else {
            refreshLayout.isEnabled = false
            view.findViewById<FloatingActionButton>(R.id.create_playlist).visibility = View.GONE
        }
    }

    override fun onResume() {
        // optimize CreatePlaylistFab bottom margin if miniPlayer active
        val createPlaylistButton = view?.findViewById<FloatingActionButton>(R.id.create_playlist)
        val layoutParams = createPlaylistButton?.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin = if (isMiniPlayerVisible) 180 else 64
        createPlaylistButton?.layoutParams = layoutParams
        super.onResume()
    }

    fun fetchPlaylists() {
        fun run() {
            refreshLayout.isRefreshing = true
            lifecycleScope.launchWhenCreated {
                val response = try {
                    RetrofitInstance.api.playlists(token)
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response")
                    Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                } finally {
                    refreshLayout.isRefreshing = false
                }
                if (response.isNotEmpty()) {
                    runOnUiThread {
                        view?.findViewById<ImageView>(R.id.boogh2)?.visibility = View.GONE
                        view?.findViewById<TextView>(R.id.textLike2)?.visibility = View.GONE
                    }
                    val playlistsAdapter = PlaylistsAdapter(
                        response.toMutableList(),
                        requireActivity()
                    )
                    playlistRecyclerView.adapter = playlistsAdapter
                } else {
                    runOnUiThread {
                        view?.findViewById<ImageView>(R.id.boogh2).apply {
                            this?.visibility = View.VISIBLE
                            this?.setImageResource(R.drawable.ic_list)
                        }
                        view?.findViewById<TextView>(R.id.textLike2).apply {
                            this?.visibility = View.VISIBLE
                            this?.text = getString(R.string.emptyList)
                        }
                    }
                }
            }
        }
        run()
    }

    private fun Fragment?.runOnUiThread(action: () -> Unit) {
        this ?: return
        if (!isAdded) return // Fragment not attached to an Activity
        activity?.runOnUiThread(action)
    }
}
