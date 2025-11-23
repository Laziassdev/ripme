package com.rarchives.ripme.ui;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.*;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.uiUtils.ContextActionProtections;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

/**
 * Everything UI-related starts and ends here.
 */
public final class MainWindow implements Runnable, RipStatusHandler {

    private static final Logger LOGGER = LogManager.getLogger(MainWindow.class);

    private static final Set<String> MAIN_CONFIG_KEYS = new HashSet<>(Arrays.asList(
            "threads.size", "download.timeout", "download.retries", "download.retry.sleep", "file.overwrite",
            "auto.update", "play.sound", "download.show_popup", "download.save_order", "log.save",
            "urls_only.save", "album_titles.save", "clipboard.autorip", "descriptions.save", "prefer.mp4",
            "window.position", "remember.url_history", "ssl.verify.off", "lang", "log.level",
            "rips.directory"));

    /* not static! */
    private boolean isRipping = false; // Flag to indicate if we're ripping something
    private final Map<AbstractRipper, String> activeRippers = new ConcurrentHashMap<>();
    private final Set<String> activeDomains = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService ripExecutor = Executors.newCachedThreadPool();
    private BiConsumer<String, String> ripperLauncher = this::launchRipper;

    private static JFrame mainFrame;

    private static JTextField ripTextfield;
    private static JButton ripButton, stopButton;

    private static JLabel statusLabel;
    private static JButton openButton;
    private static JProgressBar statusProgress;

    // Put an empty JPanel on the bottom of the window to keep components
    // anchored to the top when there is no open lower panel
    private static JPanel emptyPanel;

    // Log
    private static JButton optionLog;
    private static JPanel logPanel;
    private static JTextPane logText;

    // History
    private static JButton optionHistory;
    private static final History HISTORY = new History();
    private static JPanel historyPanel;
    private static JTable historyTable;
    private static AbstractTableModel historyTableModel;
    private static JButton historyButtonRemove, historyButtonClear, historyButtonRerip;

    // Queue
    public static JButton optionQueue;
    private static JPanel queuePanel;
    private static DefaultListModel<Object> queueListModel;
    private static JList<Object> queueList;
    private static QueueMenuMouseListener queueMenuMouseListener;
    private static JButton queueButtonTop, queueButtonUp, queueButtonDown;

    // Active downloads
    private static JButton optionActive;
    private static JPanel activePanel;
    private static JPanel activeListPanel;

    // Configuration
    private static JButton optionConfiguration;
    private static JPanel configurationPanel;
    private static JPanel configMainPanel;
    private static JPanel configOtherPanel;
    private static JPanel configCards;
    private static JButton configBackButton;
    private static JButton configNextButton;
    private static CardLayout configCardLayout;
    private static JButton configUpdateButton;
    private static JLabel configUpdateLabel;
    private static JTextField configTimeoutText;
    private static JTextField configThreadsText;
    private static JCheckBox configOverwriteCheckbox;
    private static JLabel configSaveDirLabel;
    private static JButton configSaveDirButton;
    private static JTextField configRetriesText;

    /* not static */
    private JTextField configRetrySleepText;

    private static JCheckBox configAutoupdateCheckbox;
    private static JComboBox<String> configLogLevelCombobox;
    private static JCheckBox configURLHistoryCheckbox;
    private static JCheckBox configSSLVerifyOff;
    private static JCheckBox configPlaySound;
    private static JCheckBox configSaveOrderCheckbox;
    private static JCheckBox configShowPopup;
    private static JCheckBox configSaveLogs;
    private static JCheckBox configSaveURLsOnly;
    private static JCheckBox configSaveAlbumTitles;
    private static JCheckBox configClipboardAutorip;
    private static JCheckBox configSaveDescriptions;
    private static JCheckBox configPreferMp4;
    private static JCheckBox configWindowPosition;
    private static JComboBox<String> configSelectLangComboBox;
    private static JLabel configThreadsLabel;
    private static JLabel configTimeoutLabel;
    private static JLabel configRetriesLabel;
    private static JLabel configRetrySleepLabel;
    // This doesn't really belong here but I have no idea where else to put it
    private static JButton configUrlFileChooserButton;

    private static TrayIcon trayIcon;
    private static MenuItem trayMenuMain;
    private static CheckboxMenuItem trayMenuAutorip;

    private static Image mainIcon;

    private static AbstractRipper ripper;

    private void updateQueue(DefaultListModel<Object> model) {
        if (model == null)
            model = queueListModel;

        if (model.size() > 0) {
            Utils.setConfigList("queue", model.elements());
            Utils.saveConfig();
        }

        MainWindow.optionQueue.setText(String.format("%s%s", Utils.getLocalizedString("queue"),
                model.size() == 0 ? "" : "(" + model.size() + ")"));
    }

    private void updateQueue() {
        updateQueue(null);
    }

    private void refreshActivePanel() {
        SwingUtilities.invokeLater(() -> {
            activeListPanel.removeAll();
            if (activeRippers.isEmpty()) {
                JLabel emptyLabel = new JLabel(Utils.getLocalizedString("active.none"));
                emptyLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
                activeListPanel.add(emptyLabel);
            } else {
                activeRippers.forEach((ripperEntry, domain) -> {
                    JPanel rowPanel = new JPanel(new GridBagLayout());
                    rowPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
                    GridBagConstraints rowGbc = new GridBagConstraints();
                    rowGbc.gridy = 0;
                    rowGbc.insets = new Insets(0, 0, 0, 5);
                    rowGbc.fill = GridBagConstraints.HORIZONTAL;
                    rowGbc.weightx = 1;
                    JLabel urlLabel = new JLabel(ripperEntry.getURL().toString());
                    urlLabel.setToolTipText(ripperEntry.getURL().toString());
                    rowPanel.add(urlLabel, rowGbc);

                    rowGbc.gridx = 1;
                    rowGbc.weightx = 0;
                    JLabel domainLabel = new JLabel(
                            String.format("%s: %s", Utils.getLocalizedString("active.domain"), domain));
                    rowPanel.add(domainLabel, rowGbc);

                    rowGbc.gridx = 2;
                    JButton cancelButton = new JButton(Utils.getLocalizedString("cancel"));
                    cancelButton.addActionListener(e -> cancelRipper(ripperEntry));
                    rowPanel.add(cancelButton, rowGbc);

                    activeListPanel.add(rowPanel);
                });
            }
            activeListPanel.revalidate();
            activeListPanel.repaint();
            pack();
        });
    }

    private void cancelRipper(AbstractRipper ripper) {
        String domain = activeRippers.get(ripper);
        if (domain != null) {
            ripper.stop();
            onRipperFinished(domain, ripper);
        }
        refreshActivePanel();
    }

    private static void addCheckboxListener(JCheckBox checkBox, String configString) {
        checkBox.addActionListener(arg0 -> {
            Utils.setConfigBoolean(configString, checkBox.isSelected());
            Utils.configureLogger();
        });
    }

    private static JCheckBox addNewCheckbox(String text, String configString, Boolean configBool) {
        JCheckBox checkbox = new JCheckBox(text, Utils.getConfigBoolean(configString, configBool));
        checkbox.setHorizontalAlignment(JCheckBox.RIGHT);
        checkbox.setHorizontalTextPosition(JCheckBox.LEFT);
        return checkbox;
    }

    public static void addUrlToQueue(String url) {
        queueListModel.addElement(url);
    }

    public MainWindow() throws IOException {
        this(false);
    }

    MainWindow(boolean headless) throws IOException {
        if (headless) {
            initializeHeadlessComponents();
            return;
        }

        mainFrame = new JFrame("RipMe v" + UpdateUtils.getThisJarVersion());
        mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainFrame.setLayout(new GridBagLayout());

        createUI(mainFrame.getContentPane());
        pack();

        loadHistory();
        setupHandlers();

        Thread shutdownThread = new Thread(this::shutdownCleanup);
        Runtime.getRuntime().addShutdownHook(shutdownThread);

        if (Utils.getConfigBoolean("auto.update", true)) {
            upgradeProgram();
        }

        boolean autoripEnabled = Utils.getConfigBoolean("clipboard.autorip", false);
        ClipboardUtils.setClipboardAutoRip(autoripEnabled);
        trayMenuAutorip.setState(autoripEnabled);
    }

    private void initializeHeadlessComponents() {
        queueListModel = new DefaultListModel<>();
        queueList = new JList<>(queueListModel);
        optionQueue = new JButton(Utils.getLocalizedString("queue"));
        stopButton = new JButton();
        statusProgress = new JProgressBar();
    }

    private void upgradeProgram() {
        if (!configurationPanel.isVisible()) {
            optionConfiguration.doClick();
        }
        Runnable r = () -> UpdateUtils.updateProgramGUI(configUpdateLabel);
        new Thread(r).start();
    }

    public void run() {
        pack();
        restoreWindowPosition(mainFrame);
        mainFrame.setVisible(true);
    }

    private void shutdownCleanup() {
        Utils.setConfigBoolean("file.overwrite", configOverwriteCheckbox.isSelected());
        Utils.setConfigInteger("threads.size", Integer.parseInt(configThreadsText.getText()));
        Utils.setConfigInteger("download.retries", Integer.parseInt(configRetriesText.getText()));
        Utils.setConfigInteger("download.timeout", Integer.parseInt(configTimeoutText.getText()));
        Utils.setConfigBoolean("clipboard.autorip", ClipboardUtils.getClipboardAutoRip());
        Utils.setConfigBoolean("auto.update", configAutoupdateCheckbox.isSelected());
        Utils.setConfigString("log.level", configLogLevelCombobox.getSelectedItem().toString());
        Utils.setConfigBoolean("play.sound", configPlaySound.isSelected());
        Utils.setConfigBoolean("download.save_order", configSaveOrderCheckbox.isSelected());
        Utils.setConfigBoolean("download.show_popup", configShowPopup.isSelected());
        Utils.setConfigBoolean("log.save", configSaveLogs.isSelected());
        Utils.setConfigBoolean("urls_only.save", configSaveURLsOnly.isSelected());
        Utils.setConfigBoolean("album_titles.save", configSaveAlbumTitles.isSelected());
        Utils.setConfigBoolean("clipboard.autorip", configClipboardAutorip.isSelected());
        Utils.setConfigBoolean("descriptions.save", configSaveDescriptions.isSelected());
        Utils.setConfigBoolean("prefer.mp4", configPreferMp4.isSelected());
        Utils.setConfigBoolean("remember.url_history", configURLHistoryCheckbox.isSelected());
        Utils.setConfigBoolean("ssl.verify.off", configSSLVerifyOff.isSelected());
        Utils.setConfigString("lang", configSelectLangComboBox.getSelectedItem().toString());
        saveWindowPosition(mainFrame);
        saveHistory();
        Utils.saveConfig();
    }

    private void status(String text) {
        statusWithColor(text, Color.BLACK);
    }

    private void error(String text) {
        statusWithColor(text, Color.RED);
    }

    private void statusWithColor(String text, Color color) {
        statusLabel.setForeground(color);
        statusLabel.setText(text);
        pack();
    }

    private void pack() {
        SwingUtilities.invokeLater(() -> {
            Dimension preferredSize = mainFrame.getPreferredSize();
            mainFrame.setMinimumSize(preferredSize);
            if (isCollapsed()) {
                mainFrame.setSize(preferredSize);
            }
        });
    }

    private boolean isCollapsed() {
        return (!logPanel.isVisible() && !historyPanel.isVisible() && !queuePanel.isVisible()
                && !configurationPanel.isVisible() && !activePanel.isVisible());
    }

    private void createUI(Container pane) {
        // If creating the tray icon fails, ignore it.
        try {
            setupTrayIcon();
        } catch (Exception e) {
            LOGGER.warn(e.getMessage());
        }

        EmptyBorder emptyBorder = new EmptyBorder(5, 5, 5, 5);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.ipadx = 2;
        gbc.gridx = 0;
        gbc.weighty = 0;
        gbc.ipady = 2;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.PAGE_START;

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | UnsupportedLookAndFeelException
                | IllegalAccessException e) {
            LOGGER.error("[!] Exception setting system theme:", e);
        }

        ripTextfield = new JTextField("", 20);
        ripTextfield.addMouseListener(new ContextMenuMouseListener(ripTextfield));

        // Add keyboard protection of Ctrl+V for pasting.
        ripTextfield.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == 22) { // ASCII code for Ctrl+V
                    ContextActionProtections.pasteFromClipboard(ripTextfield);
                }
            }
        });

        /*
        Alternatively, just set this, and use
        ((AbstractDocument) ripTextfield.getDocument()).setDocumentFilter(new LengthLimitDocumentFilter(256));
            private static class LengthLimitDocumentFilter extends DocumentFilter {
                private final int maxLength;

                public LengthLimitDocumentFilter(int maxLength) {
                    this.maxLength = maxLength;
                }

                @Override
                public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
        //            if ((fb.getDocument().getLength() + string.length()) <= maxLength) {
                        super.insertString(fb, offset, string.substring(0, maxLength), attr);
        //            }
                }

                @Override
                public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                    int currentLength = fb.getDocument().getLength();
                    int newLength = currentLength - length + text.length();

        //            if (newLength <= maxLength) {
                    super.replace(fb, offset, length, text.substring(0, maxLength), attrs);
        //            }
                }
            }
         */

        ImageIcon ripIcon = new ImageIcon(mainIcon);
        ripButton = new JButton("<html><font size=\"5\"><b>Rip</b></font></html>", ripIcon);
        stopButton = new JButton("<html><font size=\"5\"><b>Stop</b></font></html>");
        stopButton.setEnabled(false);
        try {
            Image stopIcon = ImageIO.read(getClass().getClassLoader().getResource("stop.png"));
            stopButton.setIcon(new ImageIcon(stopIcon));
        } catch (Exception ignored) {
        }
        JPanel ripPanel = new JPanel(new GridBagLayout());
        ripPanel.setBorder(emptyBorder);

        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 0;
        gbc.gridx = 0;
        ripPanel.add(new JLabel("URL:", JLabel.RIGHT), gbc);
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.gridx = 1;
        ripPanel.add(ripTextfield, gbc);
        gbc.weighty = 0;
        gbc.weightx = 0;
        gbc.gridx = 2;
        ripPanel.add(ripButton, gbc);
        gbc.gridx = 3;
        ripPanel.add(stopButton, gbc);
        gbc.weightx = 1;

        statusLabel = new JLabel(Utils.getLocalizedString("inactive"));
        statusLabel.setHorizontalAlignment(JLabel.CENTER);
        openButton = new JButton();
        openButton.setVisible(false);
        JPanel statusPanel = new JPanel(new GridBagLayout());
        statusPanel.setBorder(emptyBorder);

        gbc.gridx = 0;
        statusPanel.add(statusLabel, gbc);
        gbc.gridy = 1;
        statusPanel.add(openButton, gbc);
        gbc.gridy = 0;

        JPanel progressPanel = new JPanel(new GridBagLayout());
        progressPanel.setBorder(emptyBorder);
        statusProgress = new JProgressBar(0, 100);
        progressPanel.add(statusProgress, gbc);

        JPanel optionsPanel = new JPanel(new GridBagLayout());
        optionsPanel.setBorder(emptyBorder);
        optionLog = new JButton(Utils.getLocalizedString("Log"));
        optionHistory = new JButton(Utils.getLocalizedString("History"));
        optionQueue = new JButton(Utils.getLocalizedString("queue"));
        optionActive = new JButton(Utils.getLocalizedString("active.downloads"));
        optionConfiguration = new JButton(Utils.getLocalizedString("Configuration"));
        optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
        optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
        optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
        optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
        optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
        try {
            Image icon;
            icon = ImageIO.read(getClass().getClassLoader().getResource("comment.png"));
            optionLog.setIcon(new ImageIcon(icon));
            icon = ImageIO.read(getClass().getClassLoader().getResource("time.png"));
            optionHistory.setIcon(new ImageIcon(icon));
            icon = ImageIO.read(getClass().getClassLoader().getResource("list.png"));
            optionQueue.setIcon(new ImageIcon(icon));
            icon = ImageIO.read(getClass().getClassLoader().getResource("wrench.png"));
            optionActive.setIcon(new ImageIcon(icon));
            icon = ImageIO.read(getClass().getClassLoader().getResource("gear.png"));
            optionConfiguration.setIcon(new ImageIcon(icon));
        } catch (Exception e) {
            LOGGER.warn(e.getMessage());
        }
        gbc.gridx = 0;
        optionsPanel.add(optionLog, gbc);
        gbc.gridx = 1;
        optionsPanel.add(optionHistory, gbc);
        gbc.gridx = 2;
        optionsPanel.add(optionQueue, gbc);
        gbc.gridx = 3;
        optionsPanel.add(optionActive, gbc);
        gbc.gridx = 4;
        optionsPanel.add(optionConfiguration, gbc);

        logPanel = new JPanel(new GridBagLayout());
        logPanel.setBorder(emptyBorder);
        logText = new JTextPane();
        logText.setEditable(false);
        JScrollPane logTextScroll = new JScrollPane(logText);
        logTextScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logTextScroll.setPreferredSize(new Dimension(300, 250));
        logPanel.setVisible(false);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        logPanel.add(logTextScroll, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0;

        historyPanel = new JPanel(new GridBagLayout());
        historyPanel.setBorder(emptyBorder);
        historyPanel.setVisible(false);

        historyTableModel = new AbstractTableModel() {
            private static final long serialVersionUID = 1L;

            @Override
            public String getColumnName(int col) {
                return HISTORY.getColumnName(col);
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return getValueAt(0, c).getClass();
            }

            @Override
            public Object getValueAt(int row, int col) {
                return HISTORY.getValueAt(row, col);
            }

            @Override
            public int getRowCount() {
                return HISTORY.toList().size();
            }

            @Override
            public int getColumnCount() {
                return HISTORY.getColumnCount();
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return (col == 0 || col == 4);
            }

            @Override
            public void setValueAt(Object value, int row, int col) {
                if (col == 4) {
                    HISTORY.get(row).selected = (Boolean) value;
                    historyTableModel.fireTableDataChanged();
                }
            }
        };

        historyTable = new JTable(historyTableModel);
        historyTable.addMouseListener(new HistoryMenuMouseListener());
        historyTable.setAutoCreateRowSorter(true);

        for (int i = 0; i < historyTable.getColumnModel().getColumnCount(); i++) {
            int width = 130; // Default
            switch (i) {
            case 0: // URL
                width = 270;
                break;
            case 3:
                width = 40;
                break;
            case 4:
                width = 15;
                break;
            }
            historyTable.getColumnModel().getColumn(i).setPreferredWidth(width);
        }

        JScrollPane historyTableScrollPane = new JScrollPane(historyTable);
        historyButtonRemove = new JButton(Utils.getLocalizedString("remove"));
        historyButtonClear = new JButton(Utils.getLocalizedString("clear"));
        historyButtonRerip = new JButton(Utils.getLocalizedString("re-rip.checked"));
        gbc.gridx = 0;
        // History List Panel
        JPanel historyTablePanel = new JPanel(new GridBagLayout());
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        historyTablePanel.add(historyTableScrollPane, gbc);
        gbc.gridy = 0;
        historyPanel.add(historyTablePanel, gbc);
        JPanel historyButtonPanel = new JPanel(new GridBagLayout());
        historyButtonPanel.setBorder(emptyBorder);
        gbc.gridx = 0;
        historyButtonPanel.add(historyButtonRemove, gbc);
        gbc.gridx = 1;
        historyButtonPanel.add(historyButtonClear, gbc);
        gbc.gridx = 2;
        historyButtonPanel.add(historyButtonRerip, gbc);
        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        historyPanel.add(historyButtonPanel, gbc);

        queuePanel = new JPanel(new GridBagLayout());
        queuePanel.setBorder(emptyBorder);
        queuePanel.setVisible(false);
        queueListModel = new DefaultListModel<>();
        queueList = new JList<>(queueListModel);
        queueList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        queueMenuMouseListener = new QueueMenuMouseListener(d -> updateQueue(queueListModel));
        queueList.addMouseListener(queueMenuMouseListener);
        JScrollPane queueListScroll = new JScrollPane(queueList, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        activePanel = new JPanel(new GridBagLayout());
        activePanel.setBorder(emptyBorder);
        activePanel.setVisible(false);
        activeListPanel = new JPanel();
        activeListPanel.setLayout(new BoxLayout(activeListPanel, BoxLayout.Y_AXIS));
        JScrollPane activeListScroll = new JScrollPane(activeListPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        for (String item : Utils.getConfigList("queue")) {
            queueListModel.addElement(item);
        }
        updateQueue();

        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        JPanel queueListPanel = new JPanel(new GridBagLayout());
        GridBagConstraints queueGbc = new GridBagConstraints();
        queueGbc.fill = GridBagConstraints.BOTH;
        queueGbc.weighty = 1;
        queueGbc.weightx = 1;
        queueListPanel.add(queueListScroll, queueGbc);

        queueButtonUp = new JButton("\u2191");
        queueButtonUp.setToolTipText(Utils.getLocalizedString("queue.move.up"));
        queueButtonUp.addActionListener(e -> {
            int[] indices = queueList.getSelectedIndices();
            if (indices.length == 0) {
                return;
            }
            for (int i = 0; i < indices.length; i++) {
                int index = indices[i];
                if (index > 0) {
                    Object element = queueListModel.get(index);
                    queueListModel.remove(index);
                    queueListModel.add(index - 1, element);
                    indices[i] = index - 1;
                }
            }
            queueList.setSelectedIndices(indices);
            queueMenuMouseListener.updateUI();
        });

        queueButtonDown = new JButton("\u2193");
        queueButtonDown.setToolTipText(Utils.getLocalizedString("queue.move.down"));
        queueButtonDown.addActionListener(e -> {
            int[] indices = queueList.getSelectedIndices();
            if (indices.length == 0) {
                return;
            }
            for (int i = indices.length - 1; i >= 0; i--) {
                int index = indices[i];
                if (index < queueListModel.getSize() - 1) {
                    Object element = queueListModel.get(index);
                    queueListModel.remove(index);
                    queueListModel.add(index + 1, element);
                    indices[i] = index + 1;
                }
            }
            queueList.setSelectedIndices(indices);
            queueMenuMouseListener.updateUI();
        });

        queueButtonTop = new JButton("\u21A5");
        queueButtonTop.setToolTipText(Utils.getLocalizedString("queue.move.top"));
        queueButtonTop.addActionListener(e -> {
            int[] indices = queueList.getSelectedIndices();
            if (indices.length == 0) {
                return;
            }
            List<Object> selected = new ArrayList<>();
            for (int index : indices) {
                selected.add(queueListModel.get(index));
            }
            for (int i = indices.length - 1; i >= 0; i--) {
                queueListModel.remove(indices[i]);
            }
            for (int i = 0; i < selected.size(); i++) {
                queueListModel.add(i, selected.get(i));
            }
            int[] newIndices = new int[selected.size()];
            for (int i = 0; i < selected.size(); i++) {
                newIndices[i] = i;
            }
            queueList.setSelectedIndices(newIndices);
            queueMenuMouseListener.updateUI();
        });

        JPanel queueButtonPanel = new JPanel(new GridBagLayout());
        GridBagConstraints buttonGbc = new GridBagConstraints();
        buttonGbc.gridx = 0;
        buttonGbc.fill = GridBagConstraints.HORIZONTAL;
        buttonGbc.gridy = 0;
        queueButtonPanel.add(queueButtonTop, buttonGbc);
        buttonGbc.gridy = 1;
        queueButtonPanel.add(queueButtonUp, buttonGbc);
        buttonGbc.gridy = 2;
        queueButtonPanel.add(queueButtonDown, buttonGbc);

        queueGbc.gridx = 1;
        queueGbc.weightx = 0;
        queueGbc.fill = GridBagConstraints.VERTICAL;
        queueListPanel.add(queueButtonPanel, queueGbc);

        queuePanel.add(queueListPanel, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0;
        gbc.weightx = 0;

        GridBagConstraints activeGbc = new GridBagConstraints();
        activeGbc.fill = GridBagConstraints.BOTH;
        activeGbc.weightx = 1;
        activeGbc.weighty = 1;
        activePanel.add(activeListScroll, activeGbc);

        configMainPanel = new JPanel(new GridBagLayout());
        configMainPanel.setBorder(emptyBorder);

        // TODO Configuration components
        configUpdateButton = new JButton(Utils.getLocalizedString("check.for.updates"));
        configUpdateLabel = new JLabel(
                Utils.getLocalizedString("current.version") + ": " + UpdateUtils.getThisJarVersion(), JLabel.RIGHT);
        configThreadsLabel = new JLabel(Utils.getLocalizedString("max.download.threads"), JLabel.RIGHT);
        configTimeoutLabel = new JLabel(Utils.getLocalizedString("timeout.mill"), JLabel.RIGHT);
        configRetriesLabel = new JLabel(Utils.getLocalizedString("retry.download.count"), JLabel.RIGHT);
        configRetrySleepLabel = new JLabel(Utils.getLocalizedString("retry.sleep.mill"), JLabel.RIGHT);
        configThreadsText = configField("threads.size", 3);
        configTimeoutText = configField("download.timeout", 60000);
        configRetriesText = configField("download.retries", 3);
        configRetrySleepText = configField("download.retry.sleep", 5000);

        configOverwriteCheckbox = addNewCheckbox(Utils.getLocalizedString("overwrite.existing.files"), "file.overwrite",
                false);
        configAutoupdateCheckbox = addNewCheckbox(Utils.getLocalizedString("auto.update"), "auto.update", true);
        configPlaySound = addNewCheckbox(Utils.getLocalizedString("sound.when.rip.completes"), "play.sound", false);
        configShowPopup = addNewCheckbox(Utils.getLocalizedString("notification.when.rip.starts"),
                "download.show_popup", false);
        configSaveOrderCheckbox = addNewCheckbox(Utils.getLocalizedString("preserve.order"), "download.save_order",
                true);
        configSaveLogs = addNewCheckbox(Utils.getLocalizedString("save.logs"), "log.save", false);
        configSaveURLsOnly = addNewCheckbox(Utils.getLocalizedString("save.urls.only"), "urls_only.save", false);
        configSaveAlbumTitles = addNewCheckbox(Utils.getLocalizedString("save.album.titles"), "album_titles.save",
                true);
        configClipboardAutorip = addNewCheckbox(Utils.getLocalizedString("autorip.from.clipboard"), "clipboard.autorip",
                false);
        configSaveDescriptions = addNewCheckbox(Utils.getLocalizedString("save.descriptions"), "descriptions.save",
                true);
        configPreferMp4 = addNewCheckbox(Utils.getLocalizedString("prefer.mp4.over.gif"), "prefer.mp4", false);
        configWindowPosition = addNewCheckbox(Utils.getLocalizedString("restore.window.position"), "window.position",
                true);
        configURLHistoryCheckbox = addNewCheckbox(Utils.getLocalizedString("remember.url.history"),
                "remember.url_history", true);
        configSSLVerifyOff = addNewCheckbox(Utils.getLocalizedString("ssl.verify.off"),
                "ssl.verify.off", false);
        configUrlFileChooserButton = new JButton(Utils.getLocalizedString("download.url.list"));

        configLogLevelCombobox = new JComboBox<>(
                new String[] { "Log level: Error", "Log level: Warn", "Log level: Info", "Log level: Debug" });
        configSelectLangComboBox = new JComboBox<>(Utils.getSupportedLanguages());
        configSelectLangComboBox.setSelectedItem(Utils.getConfigString("lang", Utils.getSelectedLanguage()));
        configLogLevelCombobox.setSelectedItem(Utils.getConfigString("log.level", "Log level: Debug"));
        setLogLevel(configLogLevelCombobox.getSelectedItem().toString());
        configSaveDirLabel = new JLabel();
        try {
            String workingDir = (Utils.shortenPath(Utils.getWorkingDirectory()));
            configSaveDirLabel.setText(workingDir);
            configSaveDirLabel.setForeground(Color.BLUE);
            configSaveDirLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        } catch (Exception e) {
            LOGGER.error(e);
        }

        configSaveDirLabel.setToolTipText(configSaveDirLabel.getText());
        configSaveDirLabel.setHorizontalAlignment(JLabel.RIGHT);
        configSaveDirButton = new JButton(Utils.getLocalizedString("select.save.dir") + "...");

        var idx = 0;
        addItemToConfigGridBagConstraints(gbc, idx++, configUpdateLabel, configUpdateButton);
        addItemToConfigGridBagConstraints(gbc, idx++, configAutoupdateCheckbox, configLogLevelCombobox);
        addItemToConfigGridBagConstraints(gbc, idx++, configThreadsLabel, configThreadsText);
        addItemToConfigGridBagConstraints(gbc, idx++, configTimeoutLabel, configTimeoutText);
        addItemToConfigGridBagConstraints(gbc, idx++, configRetriesLabel, configRetriesText);
        addItemToConfigGridBagConstraints(gbc, idx++, configRetrySleepLabel, configRetrySleepText);
        addItemToConfigGridBagConstraints(gbc, idx++, configOverwriteCheckbox, configSaveOrderCheckbox);
        addItemToConfigGridBagConstraints(gbc, idx++, configPlaySound, configSaveLogs);
        addItemToConfigGridBagConstraints(gbc, idx++, configShowPopup, configSaveURLsOnly);
        addItemToConfigGridBagConstraints(gbc, idx++, configClipboardAutorip, configSaveAlbumTitles);
        addItemToConfigGridBagConstraints(gbc, idx++, configSaveDescriptions, configPreferMp4);
        addItemToConfigGridBagConstraints(gbc, idx++, configWindowPosition, configURLHistoryCheckbox);
        addItemToConfigGridBagConstraints(gbc, idx++, configSSLVerifyOff, configSSLVerifyOff);
        addItemToConfigGridBagConstraints(gbc, idx++, configSelectLangComboBox, configUrlFileChooserButton);
        addItemToConfigGridBagConstraints(gbc, idx++, configSaveDirLabel, configSaveDirButton);

        configOtherPanel = new JPanel(new GridBagLayout());
        configOtherPanel.setBorder(emptyBorder);

        GridBagConstraints otherGbc = new GridBagConstraints();
        otherGbc.fill = GridBagConstraints.HORIZONTAL;
        otherGbc.weightx = 1;
        otherGbc.gridy = 0;
        for (String key : Utils.getConfigKeys()) {
            if (MAIN_CONFIG_KEYS.contains(key)) {
                continue;
            }
            JLabel lbl = new JLabel(key, JLabel.RIGHT);
            JTextField field = new JTextField(Utils.getConfigString(key, ""));
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { Utils.setConfigString(key, field.getText()); }
                @Override public void removeUpdate(DocumentEvent e) { Utils.setConfigString(key, field.getText()); }
                @Override public void changedUpdate(DocumentEvent e) { Utils.setConfigString(key, field.getText()); }
            });
            otherGbc.gridx = 0;
            configOtherPanel.add(lbl, otherGbc);
            otherGbc.gridx = 1;
            configOtherPanel.add(field, otherGbc);
            otherGbc.gridy++;
        }

        configCardLayout = new CardLayout();
        configCards = new JPanel(configCardLayout);
        configCards.add(configMainPanel, "main");
        configCards.add(configOtherPanel, "other");

        configBackButton = new JButton("Back");
        configNextButton = new JButton("Next");
        configBackButton.setEnabled(false);
        configBackButton.addActionListener(e -> {
            configCardLayout.show(configCards, "main");
            configBackButton.setEnabled(false);
            configNextButton.setEnabled(true);
        });
        configNextButton.addActionListener(e -> {
            configCardLayout.show(configCards, "other");
            configBackButton.setEnabled(true);
            configNextButton.setEnabled(false);
        });

        JPanel configNavPanel = new JPanel();
        configNavPanel.add(configBackButton);
        configNavPanel.add(configNextButton);

        configurationPanel = new JPanel(new BorderLayout());
        configurationPanel.setBorder(emptyBorder);
        configurationPanel.setVisible(false);
        configurationPanel.add(configCards, BorderLayout.CENTER);
        configurationPanel.add(configNavPanel, BorderLayout.SOUTH);

        emptyPanel = new JPanel();
        emptyPanel.setPreferredSize(new Dimension(0, 0));
        emptyPanel.setSize(0, 0);

        gbc.anchor = GridBagConstraints.PAGE_START;
        gbc.weightx = 1;
        gbc.gridy = 0;
        pane.add(ripPanel, gbc);
        gbc.gridy = 1;
        pane.add(statusPanel, gbc);
        gbc.gridy = 2;
        pane.add(progressPanel, gbc);
        gbc.gridy = 3;
        pane.add(optionsPanel, gbc);
        gbc.weighty = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridy = 4;
        pane.add(logPanel, gbc);
        pane.add(historyPanel, gbc);
        pane.add(queuePanel, gbc);
        pane.add(activePanel, gbc);
        pane.add(configurationPanel, gbc);
        pane.add(emptyPanel, gbc);
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        refreshActivePanel();
    }

    private JTextField configField(String key, int defaultValue) {
        final var field = new JTextField(Integer.toString(Utils.getConfigInteger(key, defaultValue)));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                checkAndUpdate();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkAndUpdate();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                checkAndUpdate();
            }

            private void checkAndUpdate() {
                final var txt = field.getText();
                try {
                    final var newValue = Integer.parseInt(txt);
                    if (newValue > 0) {
                        Utils.setConfigInteger(key, newValue);
                    }
                } catch (final Exception e) {
                    LOGGER.warn(e.getMessage());
                }
            }
        });
        return field;
    }

    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JLabel thing1ToAdd,
            JButton thing2ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
        gbc.gridx = 1;
        configMainPanel.add(thing2ToAdd, gbc);
    }

    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JLabel thing1ToAdd,
            JTextField thing2ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
        gbc.gridx = 1;
        configMainPanel.add(thing2ToAdd, gbc);
    }

    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JCheckBox thing1ToAdd,
            JCheckBox thing2ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
        gbc.gridx = 1;
        configMainPanel.add(thing2ToAdd, gbc);
    }

    @SuppressWarnings("rawtypes")
    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JCheckBox thing1ToAdd,
            JComboBox thing2ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
        gbc.gridx = 1;
        configMainPanel.add(thing2ToAdd, gbc);
    }

    @SuppressWarnings("rawtypes")
    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JComboBox thing1ToAdd,
            JButton thing2ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
        gbc.gridx = 1;
        configMainPanel.add(thing2ToAdd, gbc);
    }

    @SuppressWarnings({ "unused", "rawtypes" })
    private void addItemToConfigGridBagConstraints(GridBagConstraints gbc, int gbcYValue, JComboBox thing1ToAdd) {
        gbc.gridy = gbcYValue;
        gbc.gridx = 0;
        configMainPanel.add(thing1ToAdd, gbc);
    }

    private void changeLocale() {
        statusLabel.setText(Utils.getLocalizedString("inactive"));
        configUpdateButton.setText(Utils.getLocalizedString("check.for.updates"));
        configUpdateLabel.setText(Utils.getLocalizedString("current.version") + ": " + UpdateUtils.getThisJarVersion());
        configThreadsLabel.setText(Utils.getLocalizedString("max.download.threads"));
        configTimeoutLabel.setText(Utils.getLocalizedString("timeout.mill"));
        configRetriesLabel.setText(Utils.getLocalizedString("retry.download.count"));
        configOverwriteCheckbox.setText(Utils.getLocalizedString("overwrite.existing.files"));
        configAutoupdateCheckbox.setText(Utils.getLocalizedString("auto.update"));
        configPlaySound.setText(Utils.getLocalizedString("sound.when.rip.completes"));
        configShowPopup.setText(Utils.getLocalizedString("notification.when.rip.starts"));
        configSaveOrderCheckbox.setText(Utils.getLocalizedString("preserve.order"));
        configSaveLogs.setText(Utils.getLocalizedString("save.logs"));
        configSaveURLsOnly.setText(Utils.getLocalizedString("save.urls.only"));
        configSaveAlbumTitles.setText(Utils.getLocalizedString("save.album.titles"));
        configClipboardAutorip.setText(Utils.getLocalizedString("autorip.from.clipboard"));
        configSaveDescriptions.setText(Utils.getLocalizedString("save.descriptions"));
        configUrlFileChooserButton.setText(Utils.getLocalizedString("download.url.list"));
        configSaveDirButton.setText(Utils.getLocalizedString("select.save.dir") + "...");
        configPreferMp4.setText(Utils.getLocalizedString("prefer.mp4.over.gif"));
        configWindowPosition.setText(Utils.getLocalizedString("restore.window.position"));
        configURLHistoryCheckbox.setText(Utils.getLocalizedString("remember.url.history"));
        configSSLVerifyOff.setText(Utils.getLocalizedString("ssl.verify.off"));
        optionLog.setText(Utils.getLocalizedString("Log"));
        optionHistory.setText(Utils.getLocalizedString("History"));
        optionQueue.setText(Utils.getLocalizedString("queue"));
        optionActive.setText(Utils.getLocalizedString("active.downloads"));
        optionConfiguration.setText(Utils.getLocalizedString("Configuration"));
        refreshActivePanel();
    }

    private void setupHandlers() {
        ripButton.addActionListener(new RipButtonHandler(this));
        ripTextfield.addActionListener(new RipButtonHandler(this));
        ripTextfield.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                try {
                    String urlText = ripTextfield.getText().trim();
                    if (urlText.isEmpty()) {
                        return;
                    }
                    if (!urlText.startsWith("http")) {
                        urlText = "http://" + urlText;
                    }
                    URL url = new URI(urlText).toURL();
                    AbstractRipper ripper = AbstractRipper.getRipper(url);
                    statusWithColor(ripper.getHost() + " album detected", Color.GREEN);
                } catch (Exception e) {
                    statusWithColor("Can't rip this URL: " + e.getMessage(), Color.RED);
                }
            }
        });

        stopButton.addActionListener(event -> {
            activeRippers.keySet().forEach(AbstractRipper::stop);
            isRipping = false;
            stopButton.setEnabled(false);
            statusProgress.setValue(0);
            statusProgress.setVisible(false);
            pack();
            statusProgress.setValue(0);
            status(Utils.getLocalizedString("download.interrupted"));
            appendLog("Download interrupted", Color.RED);
            refreshActivePanel();
        });

        optionLog.addActionListener(event -> {
            logPanel.setVisible(!logPanel.isVisible());
            emptyPanel.setVisible(!logPanel.isVisible());
            historyPanel.setVisible(false);
            queuePanel.setVisible(false);
            activePanel.setVisible(false);
            configurationPanel.setVisible(false);
            if (logPanel.isVisible()) {
                optionLog.setFont(optionLog.getFont().deriveFont(Font.BOLD));
            } else {
                optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            }
            optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            pack();
        });

        optionHistory.addActionListener(event -> {
            logPanel.setVisible(false);
            historyPanel.setVisible(!historyPanel.isVisible());
            emptyPanel.setVisible(!historyPanel.isVisible());
            queuePanel.setVisible(false);
            activePanel.setVisible(false);
            configurationPanel.setVisible(false);
            optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            if (historyPanel.isVisible()) {
                optionHistory.setFont(optionLog.getFont().deriveFont(Font.BOLD));
            } else {
                optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            }
            optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            pack();
        });

        optionQueue.addActionListener(event -> {
            logPanel.setVisible(false);
            historyPanel.setVisible(false);
            queuePanel.setVisible(!queuePanel.isVisible());
            emptyPanel.setVisible(!queuePanel.isVisible());
            activePanel.setVisible(false);
            configurationPanel.setVisible(false);
            optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            if (queuePanel.isVisible()) {
                optionQueue.setFont(optionLog.getFont().deriveFont(Font.BOLD));
            } else {
                optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            }
            optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            pack();
        });

        optionActive.addActionListener(event -> {
            logPanel.setVisible(false);
            historyPanel.setVisible(false);
            queuePanel.setVisible(false);
            activePanel.setVisible(!activePanel.isVisible());
            emptyPanel.setVisible(!activePanel.isVisible());
            configurationPanel.setVisible(false);
            optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            if (activePanel.isVisible()) {
                optionActive.setFont(optionLog.getFont().deriveFont(Font.BOLD));
            } else {
                optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            }
            optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            pack();
        });

        optionConfiguration.addActionListener(event -> {
            logPanel.setVisible(false);
            historyPanel.setVisible(false);
            queuePanel.setVisible(false);
            activePanel.setVisible(false);
            configurationPanel.setVisible(!configurationPanel.isVisible());
            emptyPanel.setVisible(!configurationPanel.isVisible());
            optionLog.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionHistory.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionQueue.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            optionActive.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            if (configurationPanel.isVisible()) {
                configCardLayout.show(configCards, "main");
                configBackButton.setEnabled(false);
                configNextButton.setEnabled(true);
                optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.BOLD));
            } else {
                optionConfiguration.setFont(optionLog.getFont().deriveFont(Font.PLAIN));
            }
            pack();
        });

        historyButtonRemove.addActionListener(event -> {
            int[] indices = historyTable.getSelectedRows();
            for (int i = indices.length - 1; i >= 0; i--) {
                int modelIndex = historyTable.convertRowIndexToModel(indices[i]);
                HISTORY.remove(modelIndex);
            }
            try {
                historyTableModel.fireTableDataChanged();
            } catch (Exception e) {
                LOGGER.warn(e.getMessage());
            }
            saveHistory();
        });

        historyButtonClear.addActionListener(event -> {
            if (Utils.getConfigBoolean("history.warn_before_delete", true)) {

                JPanel checkChoise = new JPanel();
                checkChoise.setLayout(new FlowLayout());
                JButton yesButton = new JButton("YES");
                JButton noButton = new JButton("NO");
                yesButton.setPreferredSize(new Dimension(70, 30));
                noButton.setPreferredSize(new Dimension(70, 30));
                checkChoise.add(yesButton);
                checkChoise.add(noButton);
                JFrame.setDefaultLookAndFeelDecorated(true);
                JFrame frame = new JFrame("Are you sure?");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(checkChoise);
                frame.setSize(405, 70);
                frame.setVisible(true);
                frame.setLocationRelativeTo(null);
                noButton.addActionListener(e -> frame.setVisible(false));
                yesButton.addActionListener(ed -> {
                    frame.setVisible(false);
                    Utils.clearURLHistory();
                    HISTORY.clear();
                    try {
                        historyTableModel.fireTableDataChanged();
                    } catch (Exception e) {
                        LOGGER.warn(e.getMessage());
                    }
                    saveHistory();
                });
            } else {
                Utils.clearURLHistory();
                HISTORY.clear();
                try {
                    historyTableModel.fireTableDataChanged();
                } catch (Exception e) {
                    LOGGER.warn(e.getMessage());
                }
                saveHistory();
            }
        });

        // Re-rip all history
        historyButtonRerip.addActionListener(event -> {
            if (HISTORY.isEmpty()) {
                JOptionPane.showMessageDialog(null, Utils.getLocalizedString("history.load.none"), "RipMe Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
            int added = 0;
            for (HistoryEntry entry : HISTORY.toList()) {
                if (entry.selected) {
                    added++;
                    queueListModel.addElement(entry.url);
                }
            }
            if (added == 0) {
                JOptionPane.showMessageDialog(null, Utils.getLocalizedString("history.load.none.checked"),

                        "RipMe Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        configUpdateButton.addActionListener(arg0 -> {
            Thread t = new Thread(() -> UpdateUtils.updateProgramGUI(configUpdateLabel));
            t.start();
        });

        configLogLevelCombobox.addActionListener(arg0 -> {
            String level = ((JComboBox<?>) arg0.getSource()).getSelectedItem().toString();
            setLogLevel(level);
        });

        configSelectLangComboBox.addActionListener(arg0 -> {
            String level = ((JComboBox<?>) arg0.getSource()).getSelectedItem().toString();
            Utils.setLanguage(level);
            changeLocale();
        });

        configSaveDirLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Path file;
                try {
                    file = Utils.getWorkingDirectory();
                    Desktop desktop = Desktop.getDesktop();
                    desktop.open(file.toFile());
                } catch (IOException ex) {
                    LOGGER.warn(ex.getMessage());
                }
            }
        });

        configSaveDirButton.addActionListener(arg0 -> {
            UIManager.put("FileChooser.useSystemExtensionHiding", false);
            JFileChooser jfc =  new JFileChooser(Utils.getWorkingDirectory().toString());
            LOGGER.debug("select save directory, current is:" + Utils.getWorkingDirectory());
            jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int returnVal = jfc.showDialog(null, "select directory");
            if (returnVal != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path chosenPath;
            try {
                chosenPath = jfc.getSelectedFile().toPath();
            } catch (Exception e) {
                LOGGER.error("Error while getting selected path: ", e);
                return;
            }
            configSaveDirLabel.setText(Utils.shortenPath(chosenPath));
            Utils.setConfigString("rips.directory", chosenPath.toString());
        });

        configUrlFileChooserButton.addActionListener(arg0 -> {
            UIManager.put("FileChooser.useSystemExtensionHiding", false);
            JFileChooser jfc =  new JFileChooser(Utils.getWorkingDirectory().toAbsolutePath().toString());
            jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int returnVal = jfc.showDialog(null, "Open");
            if (returnVal != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File chosenFile = jfc.getSelectedFile();
            String chosenPath;
            try {
                chosenPath = chosenFile.getCanonicalPath();
            } catch (Exception e) {
                LOGGER.error("Error while getting selected path: ", e);
                return;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(chosenPath))) {
                for (String line = br.readLine(); line != null; line = br.readLine()) {
                    line = line.trim();
                    if (line.startsWith("http")) {
                        MainWindow.addUrlToQueue(line);
                    } else {
                        LOGGER.error("Skipping url " + line + " because it looks malformed (doesn't start with http)");
                    }
                }

            } catch (IOException e) {
                LOGGER.error("Error reading file " + e.getMessage());
            }
        });

        addCheckboxListener(configSaveOrderCheckbox, "download.save_order");
        addCheckboxListener(configOverwriteCheckbox, "file.overwrite");
        addCheckboxListener(configSaveLogs, "log.save");
        addCheckboxListener(configSaveURLsOnly, "urls_only.save");
        addCheckboxListener(configURLHistoryCheckbox, "remember.url_history");
        addCheckboxListener(configSSLVerifyOff, "ssl.verify.off");
        addCheckboxListener(configSaveAlbumTitles, "album_titles.save");
        addCheckboxListener(configSaveDescriptions, "descriptions.save");
        addCheckboxListener(configPreferMp4, "prefer.mp4");
        addCheckboxListener(configWindowPosition, "window.position");

        configClipboardAutorip.addActionListener(arg0 -> {
            Utils.setConfigBoolean("clipboard.autorip", configClipboardAutorip.isSelected());
            ClipboardUtils.setClipboardAutoRip(configClipboardAutorip.isSelected());
            trayMenuAutorip.setState(configClipboardAutorip.isSelected());
            Utils.configureLogger();
        });

        queueListModel.addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent arg0) {
                updateQueue();

                if (!isRipping) {
                    ripNextAlbum();
                }
            }

            @Override
            public void contentsChanged(ListDataEvent arg0) {
            }

            @Override
            public void intervalRemoved(ListDataEvent arg0) {
            }
        });
    }

    private void setLogLevel(String level) {
        // default level is error, set in case something else is given.
        Level newLevel = Level.ERROR;
        level = level.substring(level.lastIndexOf(' ') + 1);
        switch (level) {
        case "Debug":
            newLevel = Level.DEBUG;
            break;
        case "Info":
            newLevel = Level.INFO;
            break;
        case "Warn":
            newLevel = Level.WARN;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
        loggerConfig.setLevel(newLevel);
        ctx.updateLoggers();  // This causes all Loggers to refetch information from their LoggerConfig.
    }

    private void setupTrayIcon() {
        mainFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                trayMenuMain.setLabel(Utils.getLocalizedString("tray.hide"));
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                trayMenuMain.setLabel(Utils.getLocalizedString("tray.show"));
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                trayMenuMain.setLabel(Utils.getLocalizedString("tray.hide"));
            }

            @Override
            public void windowIconified(WindowEvent e) {
                trayMenuMain.setLabel(Utils.getLocalizedString("tray.show"));
            }
        });

        PopupMenu trayMenu = new PopupMenu();
        trayMenuMain = new MenuItem(Utils.getLocalizedString("tray.hide"));
        trayMenuMain.addActionListener(arg0 -> toggleTrayClick());
        MenuItem trayMenuAbout = new MenuItem("About " + mainFrame.getTitle());
        trayMenuAbout.addActionListener(arg0 -> {
            try {
                List<String> albumRippers = Utils.getListOfAlbumRippers();
                List<String> videoRippers = Utils.getListOfVideoRippers();

                JTextArea aboutTextArea = new JTextArea();
                aboutTextArea.setEditable(false);
                aboutTextArea.setLineWrap(true);
                aboutTextArea.setWrapStyleWord(true);

                JScrollPane scrollPane = new JScrollPane(aboutTextArea);
                scrollPane.setPreferredSize(new Dimension(400, 300));

                StringBuilder aboutContent = new StringBuilder();
                aboutContent.append("Download albums from various websites:\n");
                for (String ripper : albumRippers) {
                    ripper = ripper.substring(ripper.lastIndexOf('.') + 1);
                    if (ripper.contains("Ripper")) {
                        ripper = ripper.substring(0, ripper.indexOf("Ripper"));
                    }
                    aboutContent.append("- ").append(ripper).append("\n");
                }

                aboutContent.append("\nDownload videos from video sites:\n");
                for (String ripper : videoRippers) {
                    ripper = ripper.substring(ripper.lastIndexOf('.') + 1);
                    if (ripper.contains("Ripper")) {
                        ripper = ripper.substring(0, ripper.indexOf("Ripper"));
                    }
                    aboutContent.append("- ").append(ripper).append("\n");
                }

                aboutTextArea.setText(aboutContent.toString());

                // Ensure the scroll pane starts at the top
                SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));

                JPanel aboutPanel = new JPanel(new BorderLayout());
                JLabel titleLabel = new JLabel("Download albums and videos from various websites", JLabel.CENTER);
                titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16));
                aboutPanel.add(titleLabel, BorderLayout.NORTH);
                aboutPanel.add(scrollPane, BorderLayout.CENTER);

                JLabel footerLabel = new JLabel("Do you want to visit the project homepage on GitHub?", JLabel.CENTER);
                aboutPanel.add(footerLabel, BorderLayout.SOUTH);

                int response = JOptionPane.showConfirmDialog(null, aboutPanel, mainFrame.getTitle(),
                        JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, new ImageIcon(mainIcon));
                if (response == JOptionPane.YES_OPTION) {
                    try {
                        Desktop.getDesktop().browse(URI.create("http://github.com/laziassdev/ripme"));
                    } catch (IOException e) {
                        LOGGER.error("Exception while opening project home page", e);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn(e.getMessage());
            }
        });

        MenuItem trayMenuExit = new MenuItem(Utils.getLocalizedString("tray.exit"));
        trayMenuExit.addActionListener(arg0 -> System.exit(0));
        trayMenuAutorip = new CheckboxMenuItem(Utils.getLocalizedString("tray.autorip"));
        trayMenuAutorip.addItemListener(arg0 -> {
            ClipboardUtils.setClipboardAutoRip(trayMenuAutorip.getState());
            configClipboardAutorip.setSelected(trayMenuAutorip.getState());
        });

        trayMenu.add(trayMenuMain);
        trayMenu.add(trayMenuAbout);
        trayMenu.addSeparator();
        trayMenu.add(trayMenuAutorip);
        trayMenu.addSeparator();
        trayMenu.add(trayMenuExit);
        try {
            mainIcon = ImageIO.read(getClass().getClassLoader().getResource("icon.png"));
            trayIcon = new TrayIcon(mainIcon);
            trayIcon.setToolTip(mainFrame.getTitle());
            trayIcon.setImageAutoSize(true);
            trayIcon.setPopupMenu(trayMenu);
            SystemTray.getSystemTray().add(trayIcon);
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    toggleTrayClick();
                    if (mainFrame.getExtendedState() != JFrame.NORMAL) {
                        mainFrame.setExtendedState(JFrame.NORMAL);
                    }
                    mainFrame.setAlwaysOnTop(true);
                    mainFrame.setAlwaysOnTop(false);
                }
            });
        } catch (IOException | AWTException e) {
            // TODO implement proper stack trace handling this is really just intended as a
            // placeholder until you implement proper error handling
            LOGGER.warn(e.getMessage());
        }
    }

    private void toggleTrayClick() {
        if (mainFrame.getExtendedState() == JFrame.ICONIFIED || !mainFrame.isActive() || !mainFrame.isVisible()) {
            mainFrame.setVisible(true);
            mainFrame.setAlwaysOnTop(true);
            mainFrame.setAlwaysOnTop(false);
            trayMenuMain.setLabel(Utils.getLocalizedString("tray.hide"));
        } else {
            mainFrame.setVisible(false);
            trayMenuMain.setLabel(Utils.getLocalizedString("tray.show"));
        }
    }

    /**
     * Write a line to the Log section of the GUI
     *
     * @param text  the string to log
     * @param color the color of the line
     */
    private void appendLog(final String text, final Color color) {
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setForeground(sas, color);
        StyledDocument sd = logText.getStyledDocument();
        try {
            synchronized (this) {
                sd.insertString(sd.getLength(), text + "\n", sas);
            }
        } catch (BadLocationException e) {
            LOGGER.warn(e.getMessage());
        }

        logText.setCaretPosition(sd.getLength());
    }

    /**
     * Write a line to the GUI log and the CLI log
     *
     * @param line  the string to log
     * @param color the color of the line for the GUI log
     */
    public void displayAndLogError(String line, Color color) {
        appendLog(line, color);
        LOGGER.error(line);
    }

    private void loadHistory() throws IOException {
        File historyFile = new File(Utils.getConfigDir() + "/history.json");
        HISTORY.clear();
        if (historyFile.exists()) {
            try {
                LOGGER.info(Utils.getLocalizedString("loading.history.from") + " " + historyFile.getCanonicalPath());
                HISTORY.fromFile(historyFile.getCanonicalPath());
            } catch (IOException e) {
                LOGGER.error("Failed to load history from file " + historyFile, e);
                JOptionPane.showMessageDialog(null,
                        String.format(Utils.getLocalizedString("history.load.failed.warning"), e.getMessage()),

                        "RipMe - history load failure", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            LOGGER.info(Utils.getLocalizedString("loading.history.from.configuration"));
            HISTORY.fromList(Utils.getConfigList("download.history"));
            if (HISTORY.toList().isEmpty()) {
                // Loaded from config, still no entries.
                // Guess rip history based on rip folder
                Stream<Path> stream = Files.list(Utils.getWorkingDirectory())
                        .filter(Files::isDirectory);

                stream.forEach(dir -> {
                    String url = RipUtils.urlFromDirectoryName(dir.toString());
                    if (url != null) {
                        // We found one, add it to history
                        HistoryEntry entry = new HistoryEntry();
                        entry.url = url;
                        HISTORY.add(entry);
                    }
                });
            }
        }
    }

    private void saveHistory() {
        Path historyFile = Paths.get(Utils.getConfigDir() + "/history.json");
        try {
            if (!Files.exists(historyFile)) {
                Files.createDirectories(historyFile.getParent());
                Files.createFile(historyFile);
            }

            HISTORY.toFile(historyFile.toString());
            Utils.setConfigList("download.history", Collections.emptyList());
        } catch (IOException e) {
            LOGGER.error("Failed to save history to file " + historyFile, e);
        }
    }

    synchronized void ripNextAlbum() {
        // Save current state of queue to configuration.
        Utils.setConfigList("queue", queueListModel.elements());

        LOGGER.debug("Scanning queue ({} items) with active domains: {}", queueListModel.getSize(), activeDomains);

        boolean started;
        do {
            started = false;
            for (int i = 0; i < queueListModel.size(); i++) {
                String nextAlbum = (String) queueListModel.get(i);
                String domain = getDomainFromUrl(nextAlbum);
                if (domain == null) {
                    queueListModel.remove(i);
                    updateQueue();
                    continue;
                }
                if (activeDomains.contains(domain)) {
                    LOGGER.debug("Deferring queued rip for domain {} because another ripper is active", domain);
                    continue;
                }

                queueListModel.remove(i);
                updateQueue();
                LOGGER.debug("Starting queued rip for domain {}: {}", domain, nextAlbum);
                ripperLauncher.accept(nextAlbum, domain);
                started = true;
                break;
            }
        } while (started);

        isRipping = !activeDomains.isEmpty() || !queueListModel.isEmpty();
    }

    private void launchRipper(String urlString, String domain) {
        RipperRun ripperRun = ripAlbum(urlString);
        if (ripperRun == null) {
            onRipperFinished(domain, null);
            return;
        }

        stopButton.setEnabled(true);
        activeDomains.add(domain);
        activeRippers.put(ripperRun.ripper, domain);
        refreshActivePanel();

        ripExecutor.submit(() -> {
            try {
                ripperRun.thread.run();
            } finally {
                onRipperFinished(domain, ripperRun.ripper);
            }
        });
    }

    private RipperRun ripAlbum(String urlString) {
        if (!logPanel.isVisible()) {
            optionLog.doClick();
        }
        urlString = urlString.trim();
        if (urlString.toLowerCase().startsWith("gonewild:")) {
            urlString = "http://gonewild.com/user/" + urlString.substring(urlString.indexOf(':') + 1);
        }
        if (!urlString.startsWith("http")) {
            urlString = "http://" + urlString;
        }
        URL url;
        try {
            url = new URI(urlString).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            LOGGER.error("[!] Could not generate URL for '" + urlString + "'", e);
            error("Given URL is not valid, expecting http://website.com/page/...");
            return null;
        }
        stopButton.setEnabled(true);
        statusProgress.setValue(100);
        openButton.setVisible(false);
        statusLabel.setVisible(true);
        pack();
        boolean failed = false;
        AbstractRipper ripper = null;
        try {
            ripper = AbstractRipper.getRipper(url);
            ripper.setup();
        } catch (Exception e) {
            failed = true;
            LOGGER.error("Could not find ripper for URL " + url, e);
            error(e.getMessage());
        }
        if (!failed) {
            try {
                mainFrame.setTitle("Ripping - RipMe v" + UpdateUtils.getThisJarVersion());
                status("Starting rip...");
                ripper.setObserver(this);

                String ripUrl = ripper.getURL().toExternalForm();
                if (!HISTORY.containsURL(ripUrl)) {
                    HistoryEntry entry = new HistoryEntry();
                    entry.url = ripUrl;
                    entry.dir = ripper.getWorkingDir().getAbsolutePath();
                    entry.startDate = new Date();
                    entry.modifiedDate = new Date();
                    HISTORY.add(entry);
                    historyTableModel.fireTableDataChanged();
                    saveHistory();
                }

                Thread t = new Thread(ripper);
                if (configShowPopup.isSelected() && (!mainFrame.isVisible() || !mainFrame.isActive())) {
                    try {
                        mainFrame.toFront();
                        mainFrame.setAlwaysOnTop(true);
                        trayIcon.displayMessage(mainFrame.getTitle(), "Started ripping " + ripUrl,
                                MessageType.INFO);
                        mainFrame.setAlwaysOnTop(false);
                    } catch (NullPointerException e) {
                        LOGGER.error("Could not send popup, are tray icons supported?");
                    }
                }
                return new RipperRun(ripper, t);
            } catch (Exception e) {
                LOGGER.error("[!] Error while ripping: " + e.getMessage(), e);
                error("Unable to rip this URL: " + e.getMessage());
            }
        }
        stopButton.setEnabled(false);
        statusProgress.setValue(0);
        pack();
        return null;
    }

    private String getDomainFromUrl(String urlString) {
        try {
            String trimmed = urlString.trim();
            if (!trimmed.startsWith("http")) {
                trimmed = "http://" + trimmed;
            }
            URL url = new URI(trimmed).toURL();
            return url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
        } catch (MalformedURLException | URISyntaxException e) {
            LOGGER.error("[!] Could not generate URL for '" + urlString + "'", e);
            error("Given URL is not valid, expecting http://website.com/page/...");
            return null;
        }
    }

    void onRipperFinished(String domain, AbstractRipper ripper) {
        if (ripper != null) {
            activeRippers.remove(ripper);
        }
        if (domain != null && activeDomains.remove(domain)) {
            LOGGER.debug("Completed ripper for domain {}. Remaining active domains: {}", domain, activeDomains);
        }

        refreshActivePanel();

        SwingUtilities.invokeLater(() -> {
            if (activeDomains.isEmpty()) {
                stopButton.setEnabled(false);
                statusProgress.setValue(0);
                statusProgress.setVisible(false);
            }
            LOGGER.debug("Scheduling next rip after completion of domain {}", domain);
            ripNextAlbum();
        });
    }

    void setRipperLauncher(BiConsumer<String, String> ripperLauncher) {
        if (ripperLauncher != null) {
            this.ripperLauncher = ripperLauncher;
        }
    }

    Set<String> getActiveDomains() {
        return activeDomains;
    }

    private static final class RipperRun {
        private final AbstractRipper ripper;
        private final Thread thread;

        private RipperRun(AbstractRipper ripper, Thread thread) {
            this.ripper = ripper;
            this.thread = thread;
        }
    }

    private boolean canRip(String urlString) {
        try {
            String urlText = urlString.trim();
            if (urlText.equals("")) {
                return false;
            }
            if (!urlText.startsWith("http")) {
                urlText = "http://" + urlText;
            }
            URL url = new URI(urlText).toURL();

            // Ripper is needed here to throw/not throw an Exception
            @SuppressWarnings("unused")
            AbstractRipper ripper = AbstractRipper.getRipper(url);

            return true;
        } catch (Exception e) {
            return false;
        }
    }


    public static JTextField getRipTextfield() {
        return ripTextfield;
    }

    public static DefaultListModel<Object> getQueueListModel() {
        return queueListModel;
    }

    static class RipButtonHandler implements ActionListener {
        private MainWindow mainWindow;

        public RipButtonHandler(MainWindow mainWindow) {
            this.mainWindow = mainWindow;
        }

        public void actionPerformed(ActionEvent event) {
            String url = ripTextfield.getText();
            boolean url_not_empty = !url.equals("");
            if (!queueListModel.contains(url) && url_not_empty) {
                // Check if we're ripping a range of urls
                if (url.contains("{")) {
                    // Make sure the user hasn't forgotten the closing }
                    if (url.contains("}")) {
                        String rangeToParse = url.substring(url.indexOf("{") + 1, url.indexOf("}"));
                        int rangeStart = Integer.parseInt(rangeToParse.split("-")[0]);
                        int rangeEnd = Integer.parseInt(rangeToParse.split("-")[1]);
                        for (int i = rangeStart; i < rangeEnd + 1; i++) {
                            String realURL = url.replaceAll("\\{\\S*\\}", Integer.toString(i));
                            if (mainWindow.canRip(realURL)) {
                                queueListModel.addElement(realURL);
                                ripTextfield.setText("");
                            } else {
                                mainWindow.displayAndLogError("Can't find ripper for " + realURL, Color.RED);
                            }
                        }
                    }
                } else {
                    queueListModel.addElement(url);
                    ripTextfield.setText("");
                }
            } else if (url_not_empty) {
                mainWindow.displayAndLogError("This URL is already in queue: " + url, Color.RED);
                mainWindow.statusWithColor("This URL is already in queue: " + url, Color.ORANGE);
                ripTextfield.setText("");
            }
            else if(!mainWindow.isRipping){
                mainWindow.ripNextAlbum();
            }
        }
    }

    private class StatusEvent implements Runnable {
        private final AbstractRipper ripper;
        private final RipStatusMessage msg;

        StatusEvent(AbstractRipper ripper, RipStatusMessage msg) {
            this.ripper = ripper;
            this.msg = msg;
        }

        public void run() {
            handleEvent(this);
        }
    }

    private synchronized void handleEvent(StatusEvent evt) {
        RipStatusMessage msg = evt.msg;
        if (evt.ripper.isStopped() && msg.getStatus() != RipStatusMessage.STATUS.RIP_COMPLETE) {
            return;
        }

        int completedPercent = evt.ripper.getCompletionPercentage();
        statusProgress.setValue(completedPercent);
        statusProgress.setVisible(true);
        status(evt.ripper.getStatusText());

        switch (msg.getStatus()) {
        case LOADING_RESOURCE:
        case DOWNLOAD_STARTED:
            if (LOGGER.isEnabled(Level.INFO)) {
                appendLog("Downloading " + msg.getObject(), Color.BLACK);
            }
            break;
        case DOWNLOAD_COMPLETE:
            if (LOGGER.isEnabled(Level.INFO)) {
                appendLog("Downloaded " + msg.getObject(), Color.GREEN);
            }
            break;
        case DOWNLOAD_COMPLETE_HISTORY:
            if (LOGGER.isEnabled(Level.INFO)) {
                appendLog("" + msg.getObject(), Color.GREEN);
            }
            break;

        case DOWNLOAD_ERRORED:
            if (LOGGER.isEnabled(Level.ERROR)) {
                appendLog((String) msg.getObject(), Color.RED);
            }
            break;
        case DOWNLOAD_WARN:
            if (LOGGER.isEnabled(Level.WARN)) {
                appendLog((String) msg.getObject(), Color.ORANGE);
            }
            break;
        case DOWNLOAD_SKIP:
            if (LOGGER.isEnabled(Level.INFO)) {
                appendLog((String) msg.getObject(), Color.YELLOW);
            }
            break;

        case RIP_ERRORED:
            if (LOGGER.isEnabled(Level.ERROR)) {
                appendLog((String) msg.getObject(), Color.RED);
            }
            statusProgress.setValue(0);
            statusProgress.setVisible(false);
            openButton.setVisible(false);
            pack();
            statusWithColor("Error: " + msg.getObject(), Color.RED);
            break;

        case RIP_COMPLETE:
            RipStatusComplete rsc = (RipStatusComplete) msg.getObject();
            String url = evt.ripper.getURL().toExternalForm();
            HistoryEntry entry;
            if (HISTORY.containsURL(url)) {
                entry = HISTORY.getEntryByURL(url);
                entry.count += rsc.count;
                entry.modifiedDate = new Date();
                if (entry.dir == null || entry.dir.isEmpty()) {
                    entry.dir = rsc.getDir();
                }
            } else {
                entry = new HistoryEntry();
                entry.url = url;
                entry.dir = rsc.getDir();
                entry.count = rsc.count;
                try {
                    entry.title = evt.ripper.getAlbumTitle(evt.ripper.getURL());
                } catch (MalformedURLException | URISyntaxException e) {
                    LOGGER.warn(e.getMessage());
                }
                HISTORY.add(entry);
            }
            historyTableModel.fireTableDataChanged();
            if (configPlaySound.isSelected()) {
                Utils.playSound("camera.wav");
            }
            saveHistory();
            stopButton.setEnabled(false);
            statusProgress.setValue(0);
            statusProgress.setVisible(false);
            openButton.setVisible(true);
            Path f = rsc.dir;
            String prettyFile = Utils.shortenPath(f);
            openButton.setText(Utils.getLocalizedString("open") + " " + prettyFile);
            mainFrame.setTitle("RipMe v" + UpdateUtils.getThisJarVersion());
            try {
                Image folderIcon = ImageIO.read(getClass().getClassLoader().getResource("folder.png"));
                openButton.setIcon(new ImageIcon(folderIcon));
            } catch (Exception e) {
                LOGGER.warn(e.getMessage());
            }
            /*
             * content key %path% the path to the album folder %url% is the album url
             *
             *
             */
            if (Utils.getConfigBoolean("enable.finish.command", false)) {
                try {
                    String cmdStr = Utils.getConfigString("finish.command", "ls");
                    cmdStr = cmdStr.replaceAll("%url%", url);
                    cmdStr = cmdStr.replaceAll("%path%", f.toAbsolutePath().toString());
                    // java dropped the exec string executor, as the string is only split very trivial.
                    // do the same at the moment, and split, to get rid of java-21 deprecation warning.
                    String[] commandToRun = cmdStr.split(" ");
                    LOGGER.info("RUnning command " + commandToRun);
                    Process proc = Runtime.getRuntime().exec(commandToRun);
                    BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                    BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

                    // read the output from the command
                    LOGGER.info("Command output:\n");
                    String s = null;
                    while ((s = stdInput.readLine()) != null) {
                        LOGGER.info(s);
                    }

                    // read any errors from the attempted command
                    LOGGER.error("Command error:\n");
                    while ((s = stdError.readLine()) != null) {
                        System.out.println(s);
                    }
                } catch (IOException e) {
                    LOGGER.error("Was unable to run command \"" + Utils.getConfigString("finish.command", "ls"));
                    LOGGER.error(e.getStackTrace());
                }
            }
            appendLog("Rip complete, saved to " + f, Color.GREEN);
            openButton.setActionCommand(f.toString());
            openButton.addActionListener(event -> {
                try {
                    Desktop.getDesktop().open(new File(event.getActionCommand()));
                } catch (Exception e) {
                    LOGGER.error(e);
                }
            });
            pack();
            break;
        case COMPLETED_BYTES:
            // Update completed bytes
            break;
        case TOTAL_BYTES:
            // Update total bytes
            break;
        case NO_ALBUM_OR_USER:
            if (LOGGER.isEnabled(Level.ERROR)) {
                appendLog((String) msg.getObject(), Color.RED);
            }
            statusProgress.setValue(0);
            statusProgress.setVisible(false);
            openButton.setVisible(false);
            pack();
            statusWithColor("Error: " + msg.getObject(), Color.RED);
            break;
        }
    }

    public void update(AbstractRipper ripper, RipStatusMessage message) {
        StatusEvent event = new StatusEvent(ripper, message);
        SwingUtilities.invokeLater(event);
    }

    public static void ripAlbumStatic(String url) {
        ripTextfield.setText(url.trim());
        ripButton.doClick();
    }

    private static boolean hasWindowPositionBug() {
        String osName = System.getProperty("os.name");
        // Java on Windows has a bug where if we try to manually set the position of the
        // Window,
        // javaw.exe will not close itself down when the application is closed.
        // Therefore, even if isWindowPositioningEnabled, if we are on Windows, we
        // ignore it.
        return osName == null || osName.startsWith("Windows");
    }

    private static boolean isWindowPositioningEnabled() {
        boolean isEnabled = Utils.getConfigBoolean("window.position", true);
        return isEnabled && !hasWindowPositionBug();
    }

    private static void saveWindowPosition(Frame frame) {
        if (!isWindowPositioningEnabled()) {
            return;
        }

        Point point;
        try {
            point = frame.getLocationOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                point = frame.getLocation();
            } catch (Exception e2) {
                e2.printStackTrace();
                return;
            }
        }
        int x = (int) point.getX();
        int y = (int) point.getY();
        int w = frame.getWidth();
        int h = frame.getHeight();
        Utils.setConfigInteger("window.x", x);
        Utils.setConfigInteger("window.y", y);
        Utils.setConfigInteger("window.w", w);
        Utils.setConfigInteger("window.h", h);
        LOGGER.debug("Saved window position (x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + ")");
    }

    private static void restoreWindowPosition(Frame frame) {
        if (!isWindowPositioningEnabled()) {
            mainFrame.setLocationRelativeTo(null); // default to middle of screen
            return;
        }

        try {
            int x = Utils.getConfigInteger("window.x", -1);
            int y = Utils.getConfigInteger("window.y", -1);
            int w = Utils.getConfigInteger("window.w", -1);
            int h = Utils.getConfigInteger("window.h", -1);
            if (x < 0 || y < 0 || w <= 0 || h <= 0) {
                LOGGER.debug("UNUSUAL: One or more of: x, y, w, or h was still less than 0 after reading config");
                mainFrame.setLocationRelativeTo(null); // default to middle of screen
                return;
            }
            frame.setBounds(x, y, w, h);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
