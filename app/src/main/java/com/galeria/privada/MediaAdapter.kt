package com.galeria.privada

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MediaAdapter(
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit
) : RecyclerView.Adapter<MediaAdapter.VH>() {

    var items: List<File> = emptyList()
        private set
    private val selected = LinkedHashSet<String>()

    val selecting: Boolean get() = selected.isNotEmpty()
    val selectedCount: Int get() = selected.size

    fun submit(list: List<File>) {
        items = list
        selected.retainAll(list.map { it.name }.toSet())
        notifyDataSetChanged()
    }

    fun toggle(pos: Int) {
        val n = items[pos].name
        if (!selected.add(n)) selected.remove(n)
        notifyItemChanged(pos)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedFiles(): List<File> = items.filter { it.name in selected }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val dim: View = v.findViewById(R.id.dim)
        val play: View = v.findViewById(R.id.play)
        val check: View = v.findViewById(R.id.check)

        init {
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 24f)
                }
            }
            v.clipToOutline = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        Thumbs.load(f, h.img, 360)
        val sel = f.name in selected
        h.play.visibility = if (Vault.isVideo(f)) View.VISIBLE else View.GONE
        h.dim.visibility = if (sel) View.VISIBLE else View.GONE
        h.check.visibility = if (sel) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p)
            true
        }
    }
}
