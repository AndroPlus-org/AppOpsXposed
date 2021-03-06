package at.jclehner.appopsxposed;

import java.lang.reflect.Field;
import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.app.AppOpsManager;
import android.app.ListFragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class AppListFragment extends ListFragment implements LoaderCallbacks<List<PackageInfo>>
{
	private static final String TAG = "AppListFragment";

	private AppListAdapter mAdapter;
	private LayoutInflater mInflater;

	class AppListAdapter extends BaseAdapter
	{
		private final PackageManager mPm;
		private List<PackageInfo> mList;

		public AppListAdapter(Context context) {
			mPm = context.getPackageManager();
		}

		public void setData(List<PackageInfo> list)
		{
			mList = list;
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return mList != null ? mList.size() : 0;
		}

		@Override
		public PackageInfo getItem(int position) {
			return mList.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			final ViewHolder holder;
			final ApplicationInfo appInfo = getItem(position).applicationInfo;

			if(convertView == null)
			{
				convertView = mInflater.inflate(R.layout.app_list_item, parent, false);

				holder = new ViewHolder();
				holder.appIcon = (ImageView) convertView.findViewById(R.id.app_icon);
				holder.appPackage = (TextView) convertView.findViewById(R.id.app_package);
				holder.appName = (TextView) convertView.findViewById(R.id.app_name);

				convertView.setTag(holder);
			}
			else
				holder = (ViewHolder) convertView.getTag();

			holder.appIcon.setImageDrawable(null);
			holder.appName.setText(appInfo.packageName);
			//holder.appPackage.setText(appInfo.packageName);
			//holder.appPackage.setVisibility(View.VISIBLE);

			if(true)
			{
				if(holder.task != null)
					holder.task.cancel(true);

				holder.task = new AsyncTask<Void, Void, Object[]>() {
					@Override
					protected Object[] doInBackground(Void... params)
					{
						final Object[] result = new Object[2];
						result[0] = appInfo.loadIcon(mPm);
						result[1] = appInfo.loadLabel(mPm);

						return result;
					}

					@Override
					protected void onPostExecute(Object[] result)
					{
						holder.appIcon.setImageDrawable((Drawable) result[0]);
						holder.appName.setText((CharSequence) result[1]);

						if(!appInfo.packageName.equals(result[1].toString()))
						{
							holder.appPackage.setText(appInfo.packageName);
							holder.appPackage.setVisibility(View.VISIBLE);
						}
						else
							holder.appPackage.setVisibility(View.GONE);
					}

				}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

				holder.packageName = appInfo.packageName;
			}
			else
			{
				holder.appIcon.setImageDrawable(appInfo.loadIcon(mPm));
				holder.appName.setText(appInfo.loadLabel(mPm));
			}

			return convertView;
		}
	}

	static class ViewHolder
	{
		String packageName;
		AsyncTask<Void, Void, Object[]> task;

		ImageView appIcon;
		TextView appName;
		TextView appPackage;
	}

	static class PkgInfoComparator implements Comparator<PackageInfo>
	{
		private static Collator sCollator = Collator.getInstance();
		private final PackageManager mPm;

		public PkgInfoComparator(PackageManager pm) {
			mPm = pm;
		}

		@Override
		public int compare(PackageInfo lhs, PackageInfo rhs)
		{
			return sCollator.compare(lhs.applicationInfo.loadLabel(mPm),
					rhs.applicationInfo.loadLabel(mPm));
		}
	}

	static class AppListLoader extends AsyncTaskLoader<List<PackageInfo>>
	{
		private static String[] sOpPerms;

		private final PackageManager mPm;
		private List<PackageInfo> mData;

		public AppListLoader(Context context)
		{
			super(context);
			mPm = context.getPackageManager();
			sOpPerms = getOpPermissions();
		}

		@Override
		public List<PackageInfo> loadInBackground()
		{
			List<PackageInfo> data = mPm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
			if(sOpPerms != null)
				removeAppsWithoutOps(data);
			Collections.sort(data, new PkgInfoComparator(mPm));
			return data;
		}

		@Override
		public void deliverResult(List<PackageInfo> data)
		{
			mData = data;

			if(isStarted())
				super.deliverResult(data);
		}

		@Override
		protected void onStartLoading()
		{
			onContentChanged();

			if(mData != null)
				deliverResult(mData);

			if(takeContentChanged() || mData == null)
				forceLoad();
		}

		@Override
		protected void onStopLoading() {
			cancelLoad();
		}

		@Override
		protected void onReset()
		{
			super.onReset();
			onStopLoading();
			mData = null;
		}

		private void removeAppsWithoutOps(List<PackageInfo> data)
		{
			Log.i(TAG, "removeAppsWithoutOps: ");

			for(int i = 0; i != data.size(); ++i)
			{
				if(!hasAppOps(data.get(i)))
				{
					data.remove(i);
					--i;
				}
			}
		}

		private boolean hasAppOps(PackageInfo info)
		{
			if(info.requestedPermissions != null)
			{
				for(String permName : info.requestedPermissions)
				{
					if(isAppOpsPermission(permName))
						return true;
				}
			}

			return false;
		}

		private boolean isAppOpsPermission(String permName)
		{
			for(String opPerm : sOpPerms)
			{
				if(opPerm != null && permName.equals(opPerm))
					return true;
			}

			return false;
		}

		private static String[] getOpPermissions()
		{
			try
			{
				final Field f = AppOpsManager.class.getField("sOpPerms");
				return (String[]) f.get(null);
			}
			catch(ReflectiveOperationException e)
			{
				Log.w(TAG, e);
				return null;
			}
		}
	}

	@Override
	public Loader<List<PackageInfo>> onCreateLoader(int id, Bundle args) {
		return new AppListLoader(getActivity());
	}

	@Override
	public void onLoadFinished(Loader<List<PackageInfo>> loader, List<PackageInfo> data)
	{
		mAdapter.setData(data);

		if(isResumed())
			setListShown(true);
		else
			setListShownNoAnimation(true);
	}

	@Override
	public void onLoaderReset(Loader<List<PackageInfo>> data) {
		mAdapter.setData(null);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState)
	{
		Log.d(TAG, "onActivityCreated");
		super.onActivityCreated(savedInstanceState);

		mInflater = getActivity().getLayoutInflater();
		mAdapter = new AppListAdapter(getActivity());

		setListAdapter(mAdapter);
		setListShown(false);

		getLoaderManager().initLoader(0, null, this);
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id)
	{
		final Bundle args = new Bundle();
		args.putString("package", ((ViewHolder) v.getTag()).packageName);

		final Intent intent = new Intent("android.settings.SETTINGS");
		intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, AppOpsXposed.APP_OPS_DETAILS_FRAGMENT);
		intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

		startActivity(intent);
	}
}
