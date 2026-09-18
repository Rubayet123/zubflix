package com.example.zubflix.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.R
import com.example.zubflix.utils.TmdbHelper

class CastAdapter(
    private val castList: List<TmdbHelper.CastMember>,
    private val onCastMemberClick: ((TmdbHelper.CastMember) -> Unit)? = null
) : RecyclerView.Adapter<CastAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgAvatar: ImageView = itemView.findViewById(R.id.img_avatar)
        val tvActorName: TextView = itemView.findViewById(R.id.tv_actor_name)
        val tvCharacterName: TextView = itemView.findViewById(R.id.tv_character_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = castList[position]
        holder.tvActorName.text = member.name
        holder.tvCharacterName.text = member.character ?: ""

        if (!member.profilePath.isNullOrEmpty()) {
            Glide.with(holder.itemView.context)
                .load(member.profilePath)
                .placeholder(R.drawable.ic_avatar_placeholder)
                .error(R.drawable.ic_avatar_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade())
                .centerCrop()
                .into(holder.imgAvatar)
        } else {
            holder.imgAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
        }

        holder.itemView.setOnClickListener {
            onCastMemberClick?.invoke(member)
        }

        setupTVFocus(holder.itemView)
    }

    override fun getItemCount(): Int = castList.size

    private fun setupTVFocus(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                view.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(150)
                    .start()
            } else {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
        }
    }
}
