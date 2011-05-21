package uk.co.kalgan.app.seismic;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.location.Location;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;

public class SeismicService extends Service {
	
	private Timer updateTimer;
	private float minimumMagnitude;
	
	public static final String NEW_EARTHQUAKE_FOUND = "New_Earthquake_Found";
	
	@Override
	public int onStartCommand(Intent _intent, int _flags, int _startId) {
		Context context = getApplicationContext();
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		
		int minMagIndex = prefs.getInt(UserPreferences.PREF_MIN_MAG, 0);
		if (minMagIndex < 0) minMagIndex = 0;
		
		int freqIndex = prefs.getInt(UserPreferences.PREF_UPDATE_FREQ, 0);
		if (freqIndex < 0) freqIndex = 0;
		
		boolean autoUpdate = prefs.getBoolean(UserPreferences.PREF_AUTO_UPDATE, false);
		
		Resources r = getResources();
		int[] minMagValues = r.getIntArray(R.array.magnitude_values);
		int[] freqValues = r.getIntArray(R.array.update_freq_values);
		
		minimumMagnitude = minMagValues[minMagIndex];
		int updateFreq = freqValues[freqIndex];
		
		updateTimer.cancel();
		if (autoUpdate) {
			updateTimer = new Timer("earthquakesUpdates");
			updateTimer.scheduleAtFixedRate(doRefresh, 0, updateFreq*60*1000);
		} else
			refreshEarthquakes();
		
		return Service.START_STICKY;
	}
	
	private TimerTask doRefresh = new TimerTask() {
		
		@Override
		public void run() {
			refreshEarthquakes();
		}
	};
	
	@Override
	public void onCreate() {
		updateTimer = new Timer("earthquakesUpdate");
	}

	@Override
	public IBinder onBind(Intent _intent) {
		return null;
	}

	SeismicLookupTask lastLookup = null;
	
	private void refreshEarthquakes() {
		if (lastLookup == null ||
				lastLookup.getStatus().equals(AsyncTask.Status.FINISHED)) {
			lastLookup = new SeismicLookupTask();
			lastLookup.execute((Void[])null);
		}
	}
	
	private void addNewQuake(Quake _quake) {
		ContentResolver cr = getContentResolver();
		
		// Construct a where clause to make sure we don't have this earthquake
		String w = SeismicProvider.KEY_DATE + " = " + _quake.getDate().getTime();
		
		// If earthquake is new, insert it into the provider
		Cursor c = cr.query(SeismicProvider.CONTENT_URI, null, w, null, null);
		
		if (c.getCount() == 0) {
			ContentValues values = new ContentValues();
			
			values.put(SeismicProvider.KEY_DATE, _quake.getDate().getTime());
			values.put(SeismicProvider.KEY_DETAILS, _quake.getDetails());
			
			double lat = _quake.getLocation().getLatitude();
			double lon = _quake.getLocation().getLongitude();
			values.put(SeismicProvider.KEY_LOCATION_LAT, lat);
			values.put(SeismicProvider.KEY_LOCATION_LON, lon);
			values.put(SeismicProvider.KEY_LINK, _quake.getLink());
			values.put(SeismicProvider.KEY_MAGNITUDE, _quake.getMagnitude());
			
			cr.insert(SeismicProvider.CONTENT_URI, values);
			announceNewQuake(_quake);
		}
		c.close();
	}

	private void announceNewQuake(Quake _quake) {
		Intent intent = new Intent(NEW_EARTHQUAKE_FOUND);
		intent.putExtra("date", _quake.getDate().getTime());
		intent.putExtra("details", _quake.getDetails());
		intent.putExtra("longitude", _quake.getLocation().getLongitude());
		intent.putExtra("latitude", _quake.getLocation().getLatitude());
		intent.putExtra("magnitude", _quake.getMagnitude());
		
		sendBroadcast(intent);
	}
	
	public class SeismicLookupTask extends AsyncTask<Void, Quake, Void> {

		@Override
		protected Void doInBackground(Void... _params) {
			// Get URL
			URL url;
			try {
				String quakeFeed = getString(R.string.quake_feed);
				url = new URL(quakeFeed);
				
				URLConnection connection;
				connection = url.openConnection();
				
				HttpURLConnection httpConnection = (HttpURLConnection)connection;
				int responseCode = httpConnection.getResponseCode();
				
				if (responseCode == HttpURLConnection.HTTP_OK) {
					InputStream in = httpConnection.getInputStream();
					
					DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
					DocumentBuilder db = dbf.newDocumentBuilder();
					
					// Parse feed
					Document dom = db.parse(in);
					Element docEle = dom.getDocumentElement();
					
					// Get a list of each earthquake entry
					NodeList nl = docEle.getElementsByTagName("entry");
					if (nl != null && nl.getLength() >0) {
						for (int i = 0; i < nl.getLength(); i++) {
							Element entry = (Element)nl.item(i);
							Element title = (Element)entry.getElementsByTagName("title").item(0);
							Element g = (Element)entry.getElementsByTagName("georss:point").item(0);
							Element when = (Element)entry.getElementsByTagName("updated").item(0);
							Element link = (Element)entry.getElementsByTagName("link").item(0);
							
							String details = title.getFirstChild().getNodeValue();
							String linkString = link.getAttribute("href");
							
							String point = g.getFirstChild().getNodeValue();
							String dt = when.getFirstChild().getNodeValue();
							SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss'Z'");
							Date qDate = new GregorianCalendar(0,0,0).getTime();
							try {
								qDate = sdf.parse(dt);
							} catch (ParseException e) {
								e.printStackTrace();
							}
							
							String[] location = point.split(" ");
							Location l = new Location("dummyGPS");
							l.setLatitude(Double.parseDouble(location[0]));
							l.setLongitude(Double.parseDouble(location[1]));
							
							String magnitudeString = details.split(" ")[1];
							int end = magnitudeString.length()-1;
							double magnatude = Double.parseDouble(magnitudeString.substring(0, end));
							
							details = details.split(",")[1].trim();
							
							Quake quake = new Quake(qDate, details, l, magnatude, linkString);
							
							// Process a newly found quake
							addNewQuake(quake);
							publishProgress(quake);
						}
					}
				}
			} catch (MalformedURLException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ParserConfigurationException e) {
				e.printStackTrace();
			} catch (SAXException e) {
				e.printStackTrace();
			} finally {
			}
			
			return null;
		}
		
		@Override
		protected void onProgressUpdate(Quake... _quakes ) {
			super.onProgressUpdate(_quakes);
		}
		
		@Override
		protected void onPostExecute(Void _result) {
			super.onPostExecute(_result);
		}
	}
}
