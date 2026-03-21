package com.yourname.apkcloner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yourname.apkcloner.databinding.ItemAppBinding
import com.yourname.apkcloner.util.PackageUtils

class AppListAdapter(
	private val onAppClick: (PackageUtils.AppInfo) -> Unit
) : ListAdapter<PackageUtils.AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
		val binding = ItemAppBinding.inflate(
			LayoutInflater.from(parent.context), parent, false
		)
		return AppViewHolder(binding)
	}

	override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	inner class AppViewHolder(
		private val binding: ItemAppBinding
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(appInfo: PackageUtils.AppInfo) {
			binding.appIcon.setImageDrawable(appInfo.icon)
			binding.appName.text = appInfo.label
			binding.packageName.text = appInfo.packageName

			binding.root.setOnClickListener {
				onAppClick(appInfo)
			}
		}
	}

	class AppDiffCallback : DiffUtil.ItemCallback<PackageUtils.AppInfo>() {
		override fun areItemsTheSame(
			oldItem: PackageUtils.AppInfo,
			newItem: PackageUtils.AppInfo
		): Boolean {
			return oldItem.packageName == newItem.packageName
		}

		override fun areContentsTheSame(
			oldItem: PackageUtils.AppInfo,
			newItem: PackageUtils.AppInfo
		): Boolean {
			return oldItem == newItem
		}
	}
}
