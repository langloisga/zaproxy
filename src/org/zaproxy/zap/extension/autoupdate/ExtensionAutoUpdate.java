/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2011 The Zed Attack Proxy Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.autoupdate;
import java.awt.Event;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.net.ssl.SSLHandshakeException;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileFilter;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.httpclient.URI;
import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.Extension;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.model.FileCopier;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpSender;
import org.parosproxy.paros.network.HttpStatusCode;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.control.AddOn;
import org.zaproxy.zap.control.AddOn.RunRequirements;
import org.zaproxy.zap.control.AddOnCollection;
import org.zaproxy.zap.control.AddOnCollection.Platform;
import org.zaproxy.zap.control.AddOnRunIssuesUtils;
import org.zaproxy.zap.control.AddOnUninstallationProgressCallback;
import org.zaproxy.zap.control.ExtensionFactory;
import org.zaproxy.zap.control.ZapRelease;
import org.zaproxy.zap.extension.api.API;
import org.zaproxy.zap.extension.autoupdate.AddOnDependencyChecker.AddOnChangesResult;
import org.zaproxy.zap.extension.autoupdate.UninstallationProgressDialogue.AddOnUninstallListener;
import org.zaproxy.zap.extension.autoupdate.UninstallationProgressDialogue.UninstallationProgressEvent;
import org.zaproxy.zap.extension.autoupdate.UninstallationProgressDialogue.UninstallationProgressHandler;
import org.zaproxy.zap.extension.log4j.ExtensionLog4j;
import org.zaproxy.zap.utils.ZapXmlConfiguration;
import org.zaproxy.zap.view.ScanStatus;
import org.zaproxy.zap.view.ZapMenuItem;

public class ExtensionAutoUpdate extends ExtensionAdaptor implements CheckForUpdateCallback {
	
	// The short URL means that the number of checkForUpdates can be tracked - see https://bitly.com/u/psiinon
	// Note that URLs must now use https (unless you change the code;)
    private static final String ZAP_VERSIONS_2_4_XML_SHORT = "https://bit.ly/owaspzap-2-4";
    private static final String ZAP_VERSIONS_2_4_XML_WEEKLY_SHORT = "https://bit.ly/owaspzap-2-4w";
    private static final String ZAP_VERSIONS_2_4_XML_FULL = "https://raw.githubusercontent.com/zaproxy/zap-admin/master/ZapVersions-2.4.xml";

	// URLs for use when testing locally ;)
	//private static final String ZAP_VERSIONS_2_4_XML_SHORT = "http://localhost:8080/zapcfu/ZapVersions.xml";
    //private static final String ZAP_VERSIONS_2_4_XML_WEEKLY_SHORT = "http://localhost:8080/zapcfu/ZapVersions.xml";
	//private static final String ZAP_VERSIONS_2_4_XML_FULL = "http://localhost:8080/zapcfu/ZapVersions.xml";

	private static final String VERSION_FILE_NAME = "ZapVersions.xml";

	private ZapMenuItem menuItemCheckUpdate = null;
	private ZapMenuItem menuItemLoadAddOn = null;
    
    private static final Logger logger = Logger.getLogger(ExtensionAutoUpdate.class);
    
	private HttpSender httpSender = null;

    private DownloadManager downloadManager = null;
	private ManageAddOnsDialog addonsDialog = null;
	//private UpdateDialog updateDialog = null;
	private Thread downloadProgressThread = null;
	private Thread remoteCallThread = null; 
	private ScanStatus scanStatus = null;
	private JButton addonsButton = null;
	
	private AddOnCollection latestVersionInfo = null;
	private AddOnCollection localVersionInfo = null;
	private AddOnCollection previousVersionInfo = null;

    private AutoUpdateAPI api = null;

    // Files currently being downloaded
	private List<Downloader> downloadFiles = new ArrayList<>();

    /**
     * 
     */
    public ExtensionAutoUpdate() {
        super();
 		initialize();
   }   

	/**
	 * This method initializes this
	 */
	private void initialize() {
        this.setName("ExtensionAutoUpdate");
        this.setOrder(40);
        this.downloadManager = new DownloadManager(Model.getSingleton().getOptionsParam().getConnectionParam());
        this.downloadManager.start();
        // Do this before it can get overwritten by the latest one
        this.getPreviousVersionInfo();
	}
	
	/**
	 * This method initializes menuItemEncoder	
	 * 	
	 * @return javax.swing.JMenuItem	
	 */    
	private ZapMenuItem getMenuItemCheckUpdate() {
		if (menuItemCheckUpdate == null) {
			menuItemCheckUpdate = new ZapMenuItem("cfu.help.menu.check", 
					KeyStroke.getKeyStroke(KeyEvent.VK_U, Event.CTRL_MASK, false));
			menuItemCheckUpdate.setText(Constant.messages.getString("cfu.help.menu.check"));
			menuItemCheckUpdate.addActionListener(new java.awt.event.ActionListener() { 
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e) {    
					getAddOnsDialog().setVisible(true);
					getAddOnsDialog().checkForUpdates();
				}

			});
		}
		return menuItemCheckUpdate;
	}

	private ZapMenuItem getMenuItemLoadAddOn() {
		if (menuItemLoadAddOn == null) {
			menuItemLoadAddOn = new ZapMenuItem("cfu.file.menu.loadaddon", 
					KeyStroke.getKeyStroke(KeyEvent.VK_L, Event.CTRL_MASK, false));
			menuItemLoadAddOn.addActionListener(new java.awt.event.ActionListener() { 
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e) {
					try {
						JFileChooser chooser = new JFileChooser(Model.getSingleton().getOptionsParam().getUserDirectory());
						File file = null;
						chooser.setFileFilter(new FileFilter() {
						       @Override
						       public boolean accept(File file) {
						            if (file.isDirectory()) {
						                return true;
						            } else if (file.isFile() && file.getName().endsWith(".zap")) {
						                return true;
						            }
						            return false;
						        }
						       @Override
						       public String getDescription() {
						           return Constant.messages.getString("file.format.zap.addon");
						       }
						});
						int rc = chooser.showOpenDialog(View.getSingleton().getMainFrame());
						if(rc == JFileChooser.APPROVE_OPTION) {
							file = chooser.getSelectedFile();
							if (file == null) {
								return;
							}
							installLocalAddOn(file);
						}
					} catch (Exception e1) {
						logger.error(e1.getMessage(), e1);
					}
				}
			});
		}
		return menuItemLoadAddOn;
	}
	
	private void installLocalAddOn(File file) throws Exception {
		if (!AddOn.isAddOn(file)) {
			showWarningMessageInvalidAddOnFile();
			return;
		}

		AddOn ao;
		try {
			ao = new AddOn(file);
		} catch (Exception e) {
			showWarningMessageInvalidAddOnFile();
			return;
		}

		if (!ao.canLoadInCurrentVersion()) {
			showWarningMessageCantLoadAddOn(ao);
			return;
		}

		AddOnDependencyChecker dependencyChecker = new AddOnDependencyChecker(getLocalVersionInfo(), latestVersionInfo == null
				? getLocalVersionInfo()
				: latestVersionInfo);
		AddOnChangesResult result = dependencyChecker.calculateInstallChanges(ao);

		if (result.getOldVersions().isEmpty() && result.getUninstalls().isEmpty()) {
			RunRequirements reqs = ao.calculateRunRequirements(getLocalVersionInfo().getAddOns());
			if (!reqs.isRunnable()) {
				if (!AddOnRunIssuesUtils.askConfirmationAddOnNotRunnable(
						Constant.messages.getString("cfu.warn.addOnNotRunnable.message"),
						Constant.messages.getString("cfu.warn.addOnNotRunnable.question"),
						getLocalVersionInfo(),
						ao)) {
					return;
				}
			}
			
			installLocalAddOn(ao);
			return;
		}

		if (!dependencyChecker.confirmInstallChanges(getView().getMainFrame(), result)) {
			return;
		}
		
		result.getInstalls().remove(ao);
		processAddOnChanges(getView().getMainFrame(), result);
		installLocalAddOn(ao);
	}

	private void installLocalAddOn(AddOn ao) {
		File addOnFile;
		try {
			addOnFile = copyAddOnFileToLocalPluginFolder(ao.getFile());
		} catch (FileAlreadyExistsException e) {
			showWarningMessageAddOnFileAlreadyExists(e.getFile(), e.getOtherFile());
			logger.warn("Unable to copy add-on, a file with the same name already exists.", e);
			return;
		} catch (IOException e) {
			showWarningMessageUnableToCopyAddOnFile();
			logger.warn("Unable to copy add-on to local plugin folder.", e);
			return;
		}

		ao.setFile(addOnFile);

		install(ao);
	}

	private void showWarningMessageInvalidAddOnFile() {
		View.getSingleton().showWarningDialog(Constant.messages.getString("cfu.warn.invalidAddOn"));
	}

	private void showWarningMessageCantLoadAddOn(AddOn ao) {
		String message = MessageFormat.format(
				Constant.messages.getString("cfu.warn.cantload"),
				ao.getNotBeforeVersion(),
				ao.getNotFromVersion());
		View.getSingleton().showWarningDialog(message);
	}

	private static File copyAddOnFileToLocalPluginFolder(File file) throws IOException {
		if (isFileInLocalPluginFolder(file)) {
			return file;
		}

		File targetFile = new File(Constant.FOLDER_LOCAL_PLUGIN, file.getName());
		if (targetFile.exists()) {
			throw new FileAlreadyExistsException(file.getAbsolutePath(), targetFile.getAbsolutePath(), "");
		}

		FileCopier fileCopier = new FileCopier();
		fileCopier.copy(file, targetFile);

		return targetFile;
	}

	private static boolean isFileInLocalPluginFolder(File file) {
		File fileLocalPluginFolder = new File(Constant.FOLDER_LOCAL_PLUGIN, file.getName());
		if (fileLocalPluginFolder.getAbsolutePath().equals(file.getAbsolutePath())) {
			return true;
		}
		return false;
	}

	private static void showWarningMessageAddOnFileAlreadyExists(String file, String targetFile) {
		String message = MessageFormat.format(Constant.messages.getString("cfu.warn.addOnAlreadExists"), file, targetFile);
		View.getSingleton().showWarningDialog(message);
	}

	private static void showWarningMessageUnableToCopyAddOnFile() {
		String pathPluginFolder = new File(Constant.FOLDER_LOCAL_PLUGIN).getAbsolutePath();
		String message = MessageFormat.format(Constant.messages.getString("cfu.warn.unableToCopyAddOn"), pathPluginFolder);
		View.getSingleton().showWarningDialog(message);
	}
	

	private synchronized ManageAddOnsDialog getAddOnsDialog() {
		if (addonsDialog == null) {
			addonsDialog = new ManageAddOnsDialog(this, this.getCurrentVersion(), getLocalVersionInfo());
			if (this.previousVersionInfo != null) {
				addonsDialog.setPreviousVersionInfo(this.previousVersionInfo);
			}
			if (this.latestVersionInfo != null) {
				addonsDialog.setLatestVersionInfo(this.latestVersionInfo);
			}
		}
		return addonsDialog;
	}
	
	private void downloadFile (URL url, File targetFile, long size, String hash) {
		if (View.isInitialised()) {
			// Report info to the Output tab
			View.getSingleton().getOutputPanel().append(
					MessageFormat.format(
							Constant.messages.getString("cfu.output.downloading") + "\n", 
							url.toString(),
							targetFile.getAbsolutePath()));
		}
		this.downloadFiles.add(this.downloadManager.downloadFile(url, targetFile, size, hash));
		if (View.isInitialised()) {
			// Means we do have a UI
			if (this.downloadProgressThread != null && ! this.downloadProgressThread.isAlive()) {
				this.downloadProgressThread = null;
			}
			if (this.downloadProgressThread == null) {
				this.downloadProgressThread = new Thread() {
					@Override
					public void run() {
						while (downloadManager.getCurrentDownloadCount() > 0) {
							getScanStatus().setScanCount(downloadManager.getCurrentDownloadCount());
							if (addonsDialog != null && addonsDialog.isVisible()) {
								addonsDialog.showProgress();
							}
							try {
								sleep(100);
							} catch (InterruptedException e) {
								// Ignore
							}
						}
						// Complete download progress
						if (addonsDialog != null) {
							addonsDialog.showProgress();
						}
						getScanStatus().setScanCount(0);
						installNewExtensions();
					}
				};
				this.downloadProgressThread.start();
			}
		}
	}
	
	public void installNewExtensions() {
		List<Downloader> handledFiles = new ArrayList<>();
		
		for (Downloader dl : downloadFiles) {
			if (dl.getFinished() == null) {
				continue;
			}
			handledFiles.add(dl);
			try {
				if (!dl.isValidated()) {
					logger.debug("Ignoring unvalidated download: " + dl.getUrl());
					if (addonsDialog != null) {
						addonsDialog.notifyAddOnDownloadFailed(dl.getUrl().toString());
					} else {
						String url = dl.getUrl().toString();
						for (AddOn addOn : latestVersionInfo.getAddOns()) {
							if (url.equals(addOn.getUrl().toString())) {
								addOn.setInstallationStatus(AddOn.InstallationStatus.AVAILABLE);
								break;
							}
						}
					}
				} else if (AddOn.isAddOn(dl.getTargetFile())) {
					AddOn ao = new AddOn(dl.getTargetFile());
					if (ao.canLoadInCurrentVersion()) {
						install(ao);
					} else {
			    		logger.info("Cant load add-on " + ao.getName() + 
			    				" Not before=" + ao.getNotBeforeVersion() + " Not from=" + ao.getNotFromVersion() + 
			    				" Version=" + Constant.PROGRAM_VERSION);
					}
				}
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
		
		for (Downloader dl : handledFiles) {
			// Cant remove in loop above as we're iterating through the list
			this.downloadFiles.remove(dl);
		}
	}
	
	public int getDownloadProgressPercent(URL url) throws Exception {
		return this.downloadManager.getProgressPercent(url);
	}
	
	public int getCurrentDownloadCount() {
		return this.downloadManager.getCurrentDownloadCount();
	}

	@Override
	public void hook(ExtensionHook extensionHook) {
	    super.hook(extensionHook);
	    if (getView() != null) {
	        extensionHook.getHookMenu().addHelpMenuItem(getMenuItemCheckUpdate());
	        extensionHook.getHookMenu().addFileMenuItem(getMenuItemLoadAddOn());
	        
			View.getSingleton().addMainToolbarButton(getAddonsButton());

			View.getSingleton().getMainFrame().getMainFooterPanel().addFooterToolbarRightLabel(getScanStatus().getCountLabel());
	    }
        this.api = new AutoUpdateAPI(this);
        this.api.addApiOptions(getModel().getOptionsParam().getCheckForUpdatesParam());
        API.getInstance().registerApiImplementor(this.api);
	}
	
	private ScanStatus getScanStatus() {
		if (scanStatus == null) {
	        scanStatus = new ScanStatus(
					new ImageIcon(
							ExtensionLog4j.class.getResource("/resource/icon/fugue/download.png")),
						Constant.messages.getString("cfu.downloads.icon.title"));
		}
		return scanStatus;
	}
    

	private JButton getAddonsButton() {
		if (addonsButton == null) {
			addonsButton = new JButton();
			addonsButton.setIcon(new ImageIcon(ExtensionAutoUpdate.class.getResource("/resource/icon/fugue/block.png")));
			addonsButton.setToolTipText(Constant.messages.getString("cfu.button.addons.browse"));
			addonsButton.setEnabled(true);
			addonsButton.addActionListener(new java.awt.event.ActionListener() { 
				@Override
				public void actionPerformed(java.awt.event.ActionEvent e) {
					getAddOnsDialog().setVisible(true);
				}
			});

		}
		return this.addonsButton;
	}
	
	@Override
	public String getAuthor() {
		return Constant.ZAP_TEAM;
	}

	@Override
	public String getDescription() {
		return Constant.messages.getString("autoupdate.desc");
	}

	@Override
	public URL getURL() {
		try {
			return new URL(Constant.ZAP_HOMEPAGE);
		} catch (MalformedURLException e) {
			return null;
		}
	}
	
	@Override
	public void destroy() {
		this.downloadManager.shutdown(true);
	}
	
    private HttpSender getHttpSender() {
        if (httpSender == null) {
            httpSender = new HttpSender(Model.getSingleton().getOptionsParam().getConnectionParam(), true, 
            		HttpSender.CHECK_FOR_UPDATES_INITIATOR);
        }
        return httpSender;
    }
    
    /*
     * 
     */
    public void alertIfNewVersions() {
    	// Kicks off a thread and pops up a window if there are new verisons
    	// (depending on the options the user has chosen
    	// Only expect this to be called on startup
    	
    	final OptionsParamCheckForUpdates options = getModel().getOptionsParam().getCheckForUpdatesParam();
    	
		if (View.isInitialised()) {
			if (options.isCheckOnStartUnset()) {
				// First time in
                int result = getView().showConfirmDialog(
                		Constant.messages.getString("cfu.confirm.startCheck"));
                if (result == JOptionPane.OK_OPTION) {
                	options.setCheckOnStart(true);
                	options.setCheckAddonUpdates(true);
                	options.setDownloadNewRelease(true);
                } else {
                	options.setCheckOnStart(false);
                }
                // Save
			    try {
			    	this.getModel().getOptionsParam().getConfig().save();
	            } catch (ConfigurationException ce) {
	            	logger.error(ce.getMessage(), ce);
	                getView().showWarningDialog(
	                		Constant.messages.getString("cfu.confirm.error"));
	                return;
	            }
			}
			if (! options.isCheckOnStart()) {
				return;
			}
		}
    	
		if (! options.checkOnStart()) {
			// Top level option not set, dont do anything, unless already downloaded last release
			if (View.isInitialised() && this.getPreviousVersionInfo() != null) {
				ZapRelease rel = this.getPreviousVersionInfo().getZapRelease();
				if (rel != null && rel.isNewerThan(this.getCurrentVersion())) {
					File f = new File(Constant.FOLDER_LOCAL_PLUGIN, rel.getFileName());
					if (f.exists() && f.length() >= rel.getSize()) {
						// Already downloaded, prompt to install and exit
						this.promptToLaunchReleaseAndClose(rel.getVersion(), f);
					}
				}
			}
			return;
		}
		// Handle the response in a callback
		this.getLatestVersionInfo(this);
    }
    
    
    private AddOnCollection getLocalVersionInfo () {
    	if (localVersionInfo == null) {
    		localVersionInfo = ExtensionFactory.getAddOnLoader().getAddOnCollection(); 
    	}
    	return localVersionInfo;
    }

    private ZapXmlConfiguration getRemoteConfigurationUrl(String url) throws 
    		IOException, ConfigurationException, InvalidCfuUrlException {
        HttpMessage msg = new HttpMessage(new URI(url, true));
        getHttpSender().sendAndReceive(msg,true);
        if (msg.getResponseHeader().getStatusCode() != HttpStatusCode.OK) {
            throw new IOException();
        }
        if (! msg.getRequestHeader().isSecure()) {
        	// Only access the cfu page over https
            throw new InvalidCfuUrlException(msg.getRequestHeader().getURI().toString());
        }
        
    	ZapXmlConfiguration config = new ZapXmlConfiguration();
    	config.setDelimiterParsingDisabled(true);
    	config.load(new StringReader(msg.getResponseBody().toString()));

        // Save version file so we can report new addons next time
		File f = new File(Constant.FOLDER_LOCAL_PLUGIN, VERSION_FILE_NAME);
    	FileWriter out = null;
	    try {
	    	out = new FileWriter(f);
	    	out.write(msg.getResponseBody().toString());
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
	    } finally {
	    	try {
				if (out != null) {
					out.close();
				}
			} catch (IOException e) {
				// Ignore
			}
		}

    	
    	return config;
    }

    protected String getLatestVersionNumber() {
    	if (this.getLatestVersionInfo() == null ||
    			this.getLatestVersionInfo().getZapRelease() == null) {
    		return null;
    	}
    	return this.getLatestVersionInfo().getZapRelease().getVersion();
    }
    
    protected boolean isLatestVersion() {
    	if (this.getLatestVersionInfo() == null ||
    			this.getLatestVersionInfo().getZapRelease() == null) {
    		return true;
    	}
		return  ! this.getLatestVersionInfo().getZapRelease().isNewerThan(this.getCurrentVersion());
    }
    
    protected boolean downloadLatestRelease() {
    	if (Constant.isKali()) {
    		if (View.isInitialised()) {
	    		// Just tell the user to use one of the Kali options
	    		View.getSingleton().showMessageDialog(this.getAddOnsDialog(), Constant.messages.getString("cfu.kali.options"));
    		}
    		return false;
    	}
    	if (this.getLatestVersionInfo() == null ||
    			this.getLatestVersionInfo().getZapRelease() == null) {
    		return false;
    	}
    	ZapRelease latestRelease = this.getLatestVersionInfo().getZapRelease();
		if (latestRelease.isNewerThan(this.getCurrentVersion())) {
			File f = new File(Constant.FOLDER_LOCAL_PLUGIN, latestRelease.getFileName());
			downloadFile(latestRelease.getUrl(), f, latestRelease.getSize(), latestRelease.getHash());
			return true;
		}
		return false;
    }
    
	private AddOnCollection getPreviousVersionInfo() {
		if (this.previousVersionInfo == null) {
			File f = new File(Constant.FOLDER_LOCAL_PLUGIN, VERSION_FILE_NAME);
			if (f.exists()) {
				try {
					this.previousVersionInfo = new AddOnCollection(new ZapXmlConfiguration(f), this.getPlatform());
				} catch (ConfigurationException e) {
					logger.error(e.getMessage(), e);
				} 
			}
		}
		return this.previousVersionInfo;
	}
    
	protected List<Downloader> getAllDownloadsProgress() {
		return this.downloadManager.getProgress();
	}

    
    private List<AddOn> getUpdatedAddOns() {
    	return getLocalVersionInfo().getUpdatedAddOns(this.getLatestVersionInfo());
    }
    
    private List<AddOn> getNewAddOns() {
    	if (this.getPreviousVersionInfo() != null) {
    		return this.getPreviousVersionInfo().getNewAddOns(this.getLatestVersionInfo());
    	}
    	return getLocalVersionInfo().getNewAddOns(this.getLatestVersionInfo());
    }

    protected AddOnCollection getLatestVersionInfo () {
    	return getLatestVersionInfo(null);
    }
    
    protected AddOnCollection getLatestVersionInfo (final CheckForUpdateCallback callback) {
    	if (latestVersionInfo == null) {
    		
    		if (this.remoteCallThread == null || !this.remoteCallThread.isAlive()) {
    			this.remoteCallThread = new Thread() {
    			
	    			@Override
	    			public void run() {
	    				// Using a thread as the first call could timeout
	    				// and we dont want the ui to hang in the meantime
	    				this.setName("ZAP-cfu");
						String url = ZAP_VERSIONS_2_4_XML_SHORT;
						if (Constant.isDailyBuild()) {
							url = ZAP_VERSIONS_2_4_XML_WEEKLY_SHORT;
						}
						logger.debug("Getting latest version info from " + url);
			    		try {
							latestVersionInfo = new AddOnCollection(getRemoteConfigurationUrl(url), getPlatform(), false);
						} catch (Exception e1) {
							logger.debug("Failed to access " + url, e1);
							logger.debug("Getting latest version info from " + ZAP_VERSIONS_2_4_XML_FULL);
							url = ZAP_VERSIONS_2_4_XML_FULL;
				    		try {
				    			latestVersionInfo = new AddOnCollection(getRemoteConfigurationUrl(url), getPlatform(), false);
				    		} catch (SSLHandshakeException e2) {
					    		if (callback != null) {
					    			callback.insecureUrl(url, e2);
					    		}
							} catch (InvalidCfuUrlException e2) {
					    		if (callback != null) {
					    			callback.insecureUrl(url, e2);
					    		}
							} catch (Exception e2) {
								logger.debug("Failed to access " + ZAP_VERSIONS_2_4_XML_FULL, e2);
							}
						}
			    		if (callback != null && latestVersionInfo != null) {
							logger.debug("Calling callback with  " + latestVersionInfo);
			    			callback.gotLatestData(latestVersionInfo);
			    		}
						logger.debug("Done");
	    			}
    			};
    			this.remoteCallThread.start();
    		}
    		if (callback == null) {
    			// Synchronous, but include a 30 sec max anyway
    			int i=0;
				while (latestVersionInfo == null && this.remoteCallThread.isAlive() && i < 30) {
					try {
						Thread.sleep(1000);
						i++;
					} catch (InterruptedException e) {
						// Ignore
					}
				}
    		}
    	}
    	return latestVersionInfo;
    }

    private String getCurrentVersion() {
    	// Put into local function to make it easy to manually test different scenarios;)
    	return Constant.PROGRAM_VERSION;
    }

	private Platform getPlatform() {
		if (Constant.isDailyBuild()) {
			return Platform.daily;
		} else if (Constant.isWindows()) {
			return Platform.windows;
		} else if (Constant.isLinux()) {
			return Platform.linux;
		} else  {
			return Platform.mac;
		}
	}

	protected void promptToLaunchReleaseAndClose(String version, File f) {
		int ans = View.getSingleton().showConfirmDialog(
				MessageFormat.format(
						Constant.messages.getString("cfu.confirm.launch"), 
						version,
						f.getAbsolutePath()));
		if (ans == JOptionPane.OK_OPTION) {
			Control.getSingleton().exit(false, f);		
		}
	}
	
	private void install(AddOn ao) {
		if (! ao.canLoadInCurrentVersion()) {
    		throw new IllegalArgumentException("Cant load add-on " + ao.getName() + 
    				" Not before=" + ao.getNotBeforeVersion() + " Not from=" + ao.getNotFromVersion() + 
    				" Version=" + Constant.PROGRAM_VERSION);
		}
		
		AddOn installedAddOn = this.getLocalVersionInfo().getAddOn(ao.getId());
		if (installedAddOn != null) {
			if ( ! uninstallAddOn(null, installedAddOn, true)) {
                // Cant uninstall the old version, so dont try to install the new one
	            return;
			}
		}
		logger.debug("Installing new addon " + ao.getId() + " v" + ao.getFileVersion());
		if (View.isInitialised()) {
			// Report info to the Output tab
			View.getSingleton().getOutputPanel().append(
					MessageFormat.format(
							Constant.messages.getString("cfu.output.installing") + "\n", 
							ao.getName(),
							Integer.valueOf(ao.getFileVersion())));
		}

		ExtensionFactory.getAddOnLoader().addAddon(ao);

        if (latestVersionInfo != null) {
            AddOn addOn = latestVersionInfo.getAddOn(ao.getId());
            if (AddOn.InstallationStatus.DOWNLOADING == addOn.getInstallationStatus()) {
                addOn.setInstallationStatus(AddOn.InstallationStatus.INSTALLED);
            }
        }

        if (addonsDialog != null) {
            addonsDialog.notifyAddOnInstalled(ao);
        }
	}
	
    private boolean uninstall(AddOn addOn, boolean upgrading, AddOnUninstallationProgressCallback callback) {
        logger.debug("Trying to uninstall addon " + addOn.getId() + " v" + addOn.getFileVersion());

        boolean removedDynamically = ExtensionFactory.getAddOnLoader().removeAddOn(addOn, upgrading, callback);
        if (removedDynamically) {
            logger.debug("Uninstalled add-on " + addOn.getName());

            if (latestVersionInfo != null) {
                AddOn availableAddOn = latestVersionInfo.getAddOn(addOn.getId());
                if (availableAddOn != null && availableAddOn.getInstallationStatus() != AddOn.InstallationStatus.AVAILABLE) {
                    availableAddOn.setInstallationStatus(AddOn.InstallationStatus.AVAILABLE);
                }
            }
        } else {
            logger.debug("Failed to uninstall add-on " + addOn.getId() + " v" + addOn.getFileVersion());
        }
        return removedDynamically;
    }

	@Override
	public void insecureUrl(String url, Exception cause) {
		logger.error("Failed to get check for updates on " + url, cause);
    	if (View.isInitialised()) {
    		View.getSingleton().showWarningDialog(Constant.messages.getString("cfu.warn.badurl"));
    	}
	}

	@Override
	public void gotLatestData(AddOnCollection aoc) {
		if (aoc == null) {
			return;
		}
		try {
			ZapRelease rel = aoc.getZapRelease();

	    	OptionsParamCheckForUpdates options = getModel().getOptionsParam().getCheckForUpdatesParam();

	    	if (rel.isNewerThan(getCurrentVersion())) {
				logger.debug("There is a newer release: " + rel.getVersion());
				// New ZAP release
				if (Constant.isKali()) {
		    		// Kali has its own package management system
					if (View.isInitialised()) {
						getAddOnsDialog().setVisible(true);
					}
					return;
				}
				
				File f = new File(Constant.FOLDER_LOCAL_PLUGIN, rel.getFileName());
				if (f.exists() && f.length() >= rel.getSize()) {
					// Already downloaded, prompt to install and exit
					promptToLaunchReleaseAndClose(rel.getVersion(), f);
				} else if (options.isDownloadNewRelease()) {
					logger.debug("Auto-downloading release");
					if (downloadLatestRelease() && addonsDialog != null) {
					    addonsDialog.setDownloadingZap();
					}
				} else if (addonsDialog != null) {
					// Just show the dialog
				    addonsDialog.setVisible(true);
				}
				return;
			}

			boolean keepChecking = checkForAddOnUpdates(aoc, options);

			if (keepChecking && addonsDialog != null) {
				List<AddOn> newAddOns = getNewAddOns();
				if (newAddOns.size() > 0) {
					boolean report = false;
					for (AddOn addon : newAddOns) {
						switch (addon.getStatus()) {
						case alpha:
							if (options.isReportAlphaAddons()) {
								report = true;
							}
							break;
						case beta:
							if (options.isReportBetaAddons()) {
								report = true;
							}
							break;
						case release:
							if (options.isReportReleaseAddons()) {
								report = true;
							}
							break;
						default:
							break;
						}
					}
					if (report) {
						getAddOnsDialog().setVisible(true);
						getAddOnsDialog().selectMarketplaceTab();
					}
				}
			}
		} catch (Exception e) {
			// Ignore (well, debug;), will be already logged
			logger.debug(e.getMessage(), e);
		}
	}

	private boolean checkForAddOnUpdates(AddOnCollection aoc, OptionsParamCheckForUpdates options) {
        List<AddOn> updates = getUpdatedAddOns();
        if (updates.isEmpty()) {
            return true;
        }

        logger.debug("There is/are " + updates.size() + " newer addons");
        AddOnDependencyChecker addOnDependencyChecker = new AddOnDependencyChecker(localVersionInfo, aoc);
        Set<AddOn> addOns = new HashSet<>(updates);
        AddOnDependencyChecker.AddOnChangesResult result = addOnDependencyChecker.calculateUpdateChanges(addOns);

        if (!result.getUninstalls().isEmpty() || result.isNewerJavaVersionRequired()) {
            if (options.isCheckAddonUpdates()) {
                if (addonsDialog != null) {
                    // Just show the dialog
                    getAddOnsDialog().setVisible(true);
                } else {
                    logger.info("Updates not installed some add-ons would be uninstalled or require newer java version: "
                            + result.getUninstalls());
                }
            }
            return true;
        }

        if (options.isInstallAddonUpdates()) {
            logger.debug("Auto-downloading addons");
            processAddOnChanges(null, result);
            
            return false;
        }

        if (options.isInstallScannerRules()) {
            for (Iterator<AddOn> it = addOns.iterator(); it.hasNext();) {
                if (!it.next().getId().contains("scanrules")) {
                    it.remove();
                }
            }

            logger.debug("Auto-downloading scanner rules");
            processAddOnChanges(null, addOnDependencyChecker.calculateUpdateChanges(addOns));
            return false;
        }

        return true;
	}

    /**
     * Processes the given add-on changes.
     * 
     * @param caller the caller to set as parent of shown dialogues
     * @param changes the changes that will be processed
     */
    void processAddOnChanges(Window caller, AddOnDependencyChecker.AddOnChangesResult changes) {
        if (addonsDialog != null) {
            addonsDialog.setDownloadingUpdates();
        }

        if (getView() != null) {
            Set<AddOn> addOns = new HashSet<>(changes.getUninstalls());
            addOns.addAll(changes.getOldVersions());

            if (!warnUnsavedResourcesOrActiveActions(caller, addOns, true)) {
                return;
            }
        }

        uninstallAddOns(caller, changes.getUninstalls(), false);

        Set<AddOn> allAddons = new HashSet<>(changes.getNewVersions());
        allAddons.addAll(changes.getInstalls());

        for (AddOn addOn : allAddons) {
            if (addonsDialog != null) {
                addonsDialog.notifyAddOnDownloading(addOn);
            }
            downloadAddOn(addOn);
        }
    }

    boolean warnUnsavedResourcesOrActiveActions(Window caller, Collection<AddOn> addOns, boolean updating) {
        Set<AddOn> allAddOns = new HashSet<>(addOns);
        addDependents(allAddOns);

        String baseMessagePrefix = updating ? "cfu.update." : "cfu.uninstall.";

        String unsavedResources = getExtensionsUnsavedResources(addOns);
        String activeActions = getExtensionsActiveActions(addOns);

        String message = null;
        if (!unsavedResources.isEmpty()) {
            if (activeActions.isEmpty()) {
                message = MessageFormat.format(
                        Constant.messages.getString(baseMessagePrefix + "message.resourcesNotSaved"),
                        unsavedResources);
            } else {
                message = MessageFormat.format(
                        Constant.messages.getString(baseMessagePrefix + "message.resourcesNotSavedAndActiveActions"),
                        unsavedResources,
                        activeActions);
            }
        } else if (!activeActions.isEmpty()) {
            message = MessageFormat.format(
                    Constant.messages.getString(baseMessagePrefix + "message.activeActions"),
                    activeActions);
        }

        if (message != null
                && JOptionPane.showConfirmDialog(
                        getWindowParent(caller),
                        message,
                        Constant.PROGRAM_NAME,
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return false;
        }

        return true;
    }

    private void addDependents(Set<AddOn> addOns) {
        for (AddOn availableAddOn : localVersionInfo.getInstalledAddOns()) {
            if (availableAddOn.dependsOn(addOns) && !addOns.contains(availableAddOn)) {
                addOns.add(availableAddOn);
                addDependents(addOns);
            }
        }
    }

    private Window getWindowParent(Window caller) {
        if (caller != null) {
            return caller;
        }

        if (addonsDialog != null && addonsDialog.isFocused()) {
            return addonsDialog;
        }

        return getView().getMainFrame();
    }
    
    /**
     * Returns all unsaved resources of the given {@code addOns} wrapped in {@code <li>} elements or an empty {@code String} if
     * there are no unsaved resources.
     *
     * @param addOns the add-ons that will be queried for unsaved resources
     * @return a {@code String} containing all unsaved resources or empty {@code String} if none
     * @since 2.4.0
     * @see Extension#getUnsavedResources()
     */
    private static String getExtensionsUnsavedResources(Collection<AddOn> addOns) {
        List<String> unsavedResources = new ArrayList<>();
        for (AddOn addOn : addOns) {
            for (Extension extension : addOn.getLoadedExtensions()) {
                List<String> resources = extension.getUnsavedResources();
                if (resources != null) {
                    unsavedResources.addAll(resources);
                }
            }
        }
        return wrapEntriesInLiTags(unsavedResources);
    }

    private static String wrapEntriesInLiTags(List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }

        StringBuilder strBuilder = new StringBuilder(entries.size() * 15);
        for (String entry : entries) {
            strBuilder.append("<li>");
            strBuilder.append(entry);
            strBuilder.append("</li>");
        }
        return strBuilder.toString();
    }

    /**
     * Returns all active actions of the given {@code addOns} wrapped in {@code <li>} elements or an empty {@code String} if
     * there are no active actions.
     *
     * @param addOns the add-ons that will be queried for active actions
     * @return a {@code String} containing all active actions or empty {@code String} if none
     * @since 2.4.0
     * @see Extension#getActiveActions()
     */
    private static String getExtensionsActiveActions(Collection<AddOn> addOns) {
        List<String> activeActions = new ArrayList<>();
        for (AddOn addOn : addOns) {
            for (Extension extension : addOn.getLoadedExtensions()) {
                List<String> actions = extension.getActiveActions();
                if (actions != null) {
                    activeActions.addAll(actions);
                }
            }
        }
        return wrapEntriesInLiTags(activeActions);
    }

    private void downloadAddOn(AddOn addOn) {
        if (AddOn.InstallationStatus.DOWNLOADING == addOn.getInstallationStatus()) {
            return;
        }

        addOn.setInstallationStatus(AddOn.InstallationStatus.DOWNLOADING);
        downloadFile(addOn.getUrl(), addOn.getFile(), addOn.getSize(), addOn.getHash());
    }

    private boolean uninstallAddOn(Window caller, AddOn addOn, boolean update) {
        Set<AddOn> addOns = new HashSet<>();
        addOns.add(addOn);
        return uninstallAddOns(caller, addOns, update);
    }
    
    boolean uninstallAddOns(Window caller, Set<AddOn> addOns, boolean updates) {
        if (addOns == null || addOns.isEmpty()) {
            return true;
        }

        if (getView() != null) {
            return uninstallAddOnsWithView(caller, addOns, updates, new HashSet<AddOn>());
        }

        final Set<AddOn> failedUninstallations = new HashSet<>();
        for (AddOn addOn : addOns) {
            if (!uninstall(addOn, false, null)) {
                failedUninstallations.add(addOn);
            }
        }

        if (!failedUninstallations.isEmpty()) {
            logger.warn("It's recommended to restart ZAP. Not all add-ons were successfully uninstalled: "
                    + failedUninstallations);
            return false;
        }

        return true;
    }

    boolean uninstallAddOnsWithView(
            final Window caller,
            final Set<AddOn> addOns,
            final boolean updates,
            final Set<AddOn> failedUninstallations) {
        if (addOns == null || addOns.isEmpty()) {
            return true;
        }
        
        if (!EventQueue.isDispatchThread()) {
            try {
                EventQueue.invokeAndWait(new Runnable() {
                    
                    @Override
                    public void run() {
                        uninstallAddOnsWithView(caller, addOns, updates, failedUninstallations);
                    }
                });
            } catch (InvocationTargetException | InterruptedException e) {
                logger.error("Failed to uninstall add-ons:", e);
                return false;
            }
            return failedUninstallations.isEmpty();
        }

        final Window parent = getWindowParent(caller);

        final UninstallationProgressDialogue waitDialogue = new UninstallationProgressDialogue(parent, addOns);
        waitDialogue.addAddOnUninstallListener(new AddOnUninstallListener() {

            @Override
            public void uninstallingAddOn(AddOn addOn, boolean updating) {
                if (updating) {
                    String message = MessageFormat.format(
                            Constant.messages.getString("cfu.output.replacing") + "\n",
                            addOn.getName(),
                            Integer.valueOf(addOn.getFileVersion()));
                    getView().getOutputPanel().append(message);
                }
            }

            @Override
            public void addOnUninstalled(AddOn addOn, boolean update, boolean uninstalled) {
                if (uninstalled) {
                    if (!update && addonsDialog != null) {
                        addonsDialog.notifyAddOnUninstalled(addOn);
                    }

                    String message = MessageFormat.format(
                            Constant.messages.getString("cfu.output.uninstalled") + "\n",
                            addOn.getName(),
                            Integer.valueOf(addOn.getFileVersion()));
                    getView().getOutputPanel().append(message);
                } else {
                    if (addonsDialog != null) {
                        addonsDialog.notifyAddOnFailedUninstallation(addOn);
                    }

                    String message;
                    if (update) {
                        message = MessageFormat.format(
                                Constant.messages.getString("cfu.output.replace.failed") + "\n",
                                addOn.getName(),
                                Integer.valueOf(addOn.getFileVersion()));
                    } else {
                        message = MessageFormat.format(
                                Constant.messages.getString("cfu.output.uninstall.failed") + "\n",
                                addOn.getName(),
                                Integer.valueOf(addOn.getFileVersion()));
                    }
                    getView().getOutputPanel().append(message);
                }
            }

        });

        SwingWorker<Void, UninstallationProgressEvent> a = new SwingWorker<Void, UninstallationProgressEvent>() {

            @Override
            protected void process(List<UninstallationProgressEvent> events) {
                waitDialogue.update(events);
            }

            @Override
            protected Void doInBackground() {
                UninstallationProgressHandler progressHandler = new UninstallationProgressHandler() {

                    @Override
                    protected void publishEvent(UninstallationProgressEvent event) {
                        publish(event);
                    }
                };

                for (AddOn addOn : addOns) {
                    if (!uninstall(addOn, updates, progressHandler)) {
                        failedUninstallations.add(addOn);
                    }
                }

                if (!failedUninstallations.isEmpty()) {
                    logger.warn("Not all add-ons were successfully uninstalled: " + failedUninstallations);
                }

                return null;
            }
        };

        waitDialogue.bind(a);
        a.execute();
        waitDialogue.setSynchronous(updates);
        waitDialogue.setVisible(true);

        return failedUninstallations.isEmpty();
    }

	/**
	 * No database tables used, so all supported
	 */
	@Override
	public boolean supportsDb(String type) {
    	return true;
    }
}
