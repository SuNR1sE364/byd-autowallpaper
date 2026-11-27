package com.sunr1se.byd.autowallpaper.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.sunr1se.byd.autowallpaper.R;
import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.wallpaper.VideoSpecEvaluator;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperController;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸库网格适配器：提供内存缩略图缓存、圆角裁剪以及固定/随机模式高亮标识。
 */
public class WallpaperGridAdapter extends BaseAdapter {

    public interface ItemActionListener {
        void onItemClicked(WallpaperRepository.WallpaperItem item);

        void onItemLongClicked(WallpaperRepository.WallpaperItem item);

        void onCropClicked(WallpaperRepository.WallpaperItem item);

        void onDeleteClicked(WallpaperRepository.WallpaperItem item);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final ItemActionListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbnailCache;
    private final float cornerRadiusPx;

    private final List<WallpaperRepository.WallpaperItem> items = new ArrayList<>();
    private boolean isDarkModePage = false;
    private int selectionMode = AppPrefs.SELECT_FIXED;
    private String selectedId = null;
    private String activeId = null;
    private boolean isLiveMode = true;

    public WallpaperGridAdapter(Context context, ItemActionListener listener) {
        this.context = context.getApplicationContext();
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
        this.cornerRadiusPx = 12f * context.getResources().getDisplayMetrics().density;
        this.thumbnailCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value != null ? value.getByteCount() : 0;
            }
        };
    }

    public void updateData(List<WallpaperRepository.WallpaperItem> newItems,
                           boolean isDarkPage,
                           int selectionMode,
                           String selectedId,
                           String activeId,
                           boolean isLiveMode) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        this.isDarkModePage = isDarkPage;
        this.selectionMode = selectionMode;
        this.selectedId = selectedId;
        this.activeId = activeId;
        this.isLiveMode = isLiveMode;
        notifyDataSetChanged();
    }

    public void release() {
        decodeExecutor.shutdownNow();
        thumbnailCache.evictAll();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public WallpaperRepository.WallpaperItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_wallpaper_card, parent, false);
            holder = new ViewHolder(convertView, cornerRadiusPx);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final WallpaperRepository.WallpaperItem item = items.get(position);
        holder.tvName.setText(item.displayName);

        String sizeText = VideoSpecEvaluator.formatSize(item.sizeBytes);
        if (item.isVideo()) {
            String tagText = item.durationMs > 0L
                    ? VideoSpecEvaluator.formatDuration(item.durationMs)
                    : "视频";
            holder.layoutMediaTag.setVisibility(View.VISIBLE);
            holder.ivMediaTagIcon.setImageResource(R.drawable.ic_play);
            holder.tvMediaTag.setText(tagText);
            holder.btnCrop.setVisibility(View.GONE);
            String fpsPart = item.fps > 0f ? (" · " + VideoSpecEvaluator.formatFps(item.fps)) : "";
            holder.tvMeta.setText(item.width + "×" + item.height + fpsPart + " · " + sizeText);
        } else {
            holder.btnCrop.setVisibility(View.VISIBLE);
            if (item.hasCustomCrop()) {
                holder.layoutMediaTag.setVisibility(View.VISIBLE);
                holder.ivMediaTagIcon.setImageResource(R.drawable.ic_crop);
                holder.tvMediaTag.setText("已剪裁");
            } else {
                holder.layoutMediaTag.setVisibility(View.GONE);
            }
            holder.tvMeta.setText(item.width + "×" + item.height + " · " + sizeText);
        }

        boolean unavailableInStaticMode = !isLiveMode && item.isVideo();
        if (unavailableInStaticMode) {
            holder.ivThumbnail.setAlpha(0.4f);
            holder.layoutDisabledOverlay.setVisibility(View.VISIBLE);
            holder.root.setSelected(false);
            holder.layoutBadge.setVisibility(View.GONE);
        } else {
            holder.ivThumbnail.setAlpha(1.0f);
            holder.layoutDisabledOverlay.setVisibility(View.GONE);
            boolean isFixedMode = (selectionMode == AppPrefs.SELECT_FIXED);
            boolean highlighted = isFixedMode
                    ? item.id.equals(selectedId)
                    : item.id.equals(activeId);

            holder.root.setSelected(highlighted);
            if (highlighted) {
                holder.layoutBadge.setVisibility(View.VISIBLE);
                holder.ivBadgeIcon.setImageResource(
                        isFixedMode ? R.drawable.ic_check_circle : R.drawable.ic_strategy_random);
                holder.tvBadge.setText(isFixedMode ? "使用中" : "轮换中");
            } else {
                holder.layoutBadge.setVisibility(View.GONE);
            }
        }

        holder.root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClicked(item);
            }
        });
        holder.root.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onItemLongClicked(item);
                return true;
            }
            return false;
        });
        holder.btnCrop.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCropClicked(item);
            }
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClicked(item);
            }
        });

        bindThumbnail(holder.ivThumbnail, item);
        return convertView;
    }

    private void bindThumbnail(ImageView imageView, WallpaperRepository.WallpaperItem item) {
        String cropKey = item.hasCustomCrop() ? item.cropRect.toCacheKey() : "default";
        final String cacheKey = (isDarkModePage ? "night:" : "day:") + item.id + ":" + cropKey;
        imageView.setTag(cacheKey);

        Bitmap cached = thumbnailCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageDrawable(null);
        final boolean darkFlag = isDarkModePage;
        final File file = WallpaperRepository.getThumbnailFile(context, darkFlag, item);
        decodeExecutor.execute(() -> {
            Bitmap decoded = WallpaperController.decodeSampledCroppedBitmap(
                    file, item.isVideo() ? null : item.cropRect, 480, 300);
            if (decoded != null) {
                thumbnailCache.put(cacheKey, decoded);
            }
            mainHandler.post(() -> {
                if (cacheKey.equals(imageView.getTag())) {
                    imageView.setImageBitmap(decoded);
                }
            });
        });
    }

    private static final class ViewHolder {
        final View root;
        final ImageView ivThumbnail;
        final View layoutBadge;
        final ImageView ivBadgeIcon;
        final TextView tvBadge;
        final View layoutMediaTag;
        final ImageView ivMediaTagIcon;
        final TextView tvMediaTag;
        final View layoutDisabledOverlay;
        final ImageButton btnCrop;
        final ImageButton btnDelete;
        final TextView tvName;
        final TextView tvMeta;

        ViewHolder(View view, final float cornerRadiusPx) {
            this.root = view.findViewById(R.id.wallpaperItemRoot);
            this.ivThumbnail = view.findViewById(R.id.ivItemThumbnail);
            this.layoutBadge = view.findViewById(R.id.layoutItemBadge);
            this.ivBadgeIcon = view.findViewById(R.id.ivItemBadgeIcon);
            this.tvBadge = view.findViewById(R.id.tvItemBadge);
            this.layoutMediaTag = view.findViewById(R.id.layoutItemMediaTag);
            this.ivMediaTagIcon = view.findViewById(R.id.ivItemMediaTagIcon);
            this.tvMediaTag = view.findViewById(R.id.tvItemMediaTag);
            this.layoutDisabledOverlay = view.findViewById(R.id.layoutItemDisabledOverlay);
            this.btnCrop = view.findViewById(R.id.btnItemCrop);
            this.btnDelete = view.findViewById(R.id.btnItemDelete);
            this.tvName = view.findViewById(R.id.tvItemName);
            this.tvMeta = view.findViewById(R.id.tvItemMeta);

            this.ivThumbnail.setClipToOutline(true);
            this.ivThumbnail.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadiusPx);
                }
            });
        }
    }
}
