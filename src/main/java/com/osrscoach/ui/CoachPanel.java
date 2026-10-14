package com.osrscoach.ui;

import com.osrscoach.CoachConfig;
import com.osrscoach.ai.CoachException;
import com.osrscoach.ai.CoachReply;
import com.osrscoach.ai.CoachService;
import com.osrscoach.context.AccountSnapshot;
import com.osrscoach.context.ContextFormatter;
import com.osrscoach.research.Source;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/** Sidebar UI. All mutation happens on the Swing EDT; all work is delegated to {@link CoachService}. */
public class CoachPanel extends PluginPanel
{
	private static final Color MOCK_COLOR = new Color(230, 150, 40);
	private static final Color ERROR_COLOR = new Color(220, 90, 90);
	private static final Color USER_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color COACH_BG = ColorScheme.DARK_GRAY_COLOR;

	private final CoachService service;
	private final Supplier<CoachConfig> config;
	private final ConfigManager configManager;
	private final List<String> questNames;
	private final List<String> skillNames;
	private final Consumer<String> onQuestSelected;
	private final Runnable onClearMarker;
	private final JPanel markerBar = new JPanel(new BorderLayout(4, 0));
	private final JLabel markerText = new JLabel();

	private final JLabel providerLabel = new JLabel();
	private final JLabel contextLabel = new JLabel();
	private final JPanel banner = new JPanel(new BorderLayout(4, 4));
	private final JLabel bannerText = new JLabel();
	private final ScrollablePanel chat = new ScrollablePanel();
	private final JScrollPane chatScroll;
	private final JLabel status = new JLabel(" ");
	private final JButton retry = new JButton("Retry");
	private final JTextArea input = new JTextArea(3, 10);
	private final JButton send = new JButton("Ask");
	private final JPanel quick = new JPanel(new GridLayout(3, 2, 4, 4));
	private final GridBagConstraints row = new GridBagConstraints();

	private String lastQuestion;
	private boolean busy;

	public CoachPanel(CoachService service, Supplier<CoachConfig> config, ConfigManager configManager,
		List<String> questNames, List<String> skillNames, Consumer<String> onQuestSelected, Runnable onClearMarker)
	{
		super(false);
		this.service = service;
		this.config = config;
		this.configManager = configManager;
		this.questNames = questNames;
		this.skillNames = skillNames;
		this.onQuestSelected = onQuestSelected;
		this.onClearMarker = onClearMarker;

		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ---------- top: title, provider, context ----------
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);

		JLabel title = new JLabel("OSRS Coach");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		top.add(left(title));
		providerLabel.setFont(FontManager.getRunescapeSmallFont());
		top.add(left(providerLabel));
		top.add(Box.createVerticalStrut(4));

		contextLabel.setFont(FontManager.getRunescapeSmallFont());
		contextLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		top.add(left(contextLabel));

		JPanel ctxButtons = new JPanel(new GridLayout(1, 3, 4, 0));
		ctxButtons.setOpaque(false);
		ctxButtons.add(small("Refresh", "Re-read your account", this::refreshContext));
		ctxButtons.add(small("What's sent?", "See exactly what goes to the AI", this::showPreview));
		ctxButtons.add(small("About me", "Budget, goals, membership", this::editProfile));
		top.add(left(ctxButtons));
		top.add(Box.createVerticalStrut(6));

		// quest-complete banner (optional, shown at most once per quest)
		banner.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		banner.setBorder(new EmptyBorder(4, 6, 4, 6));
		bannerText.setFont(FontManager.getRunescapeSmallFont());
		banner.add(bannerText, BorderLayout.CENTER);
		JPanel bannerButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		bannerButtons.setOpaque(false);
		bannerButtons.add(small("Ask", null, () ->
		{
			banner.setVisible(false);
			ask("I just finished a quest. What should I work toward now?");
		}));
		bannerButtons.add(small("Dismiss", null, () -> banner.setVisible(false)));
		banner.add(bannerButtons, BorderLayout.SOUTH);
		banner.setVisible(false);
		top.add(left(banner));
		top.add(Box.createVerticalStrut(4));

		// active destination marker
		markerBar.setBackground(new Color(60, 50, 15));
		markerBar.setBorder(new EmptyBorder(4, 6, 4, 6));
		markerText.setFont(FontManager.getRunescapeSmallFont());
		markerText.setForeground(new Color(255, 205, 60));
		markerBar.add(markerText, BorderLayout.CENTER);
		markerBar.add(small("Clear", "Remove the map pin and arrow", onClearMarker), BorderLayout.EAST);
		markerBar.setVisible(false);
		top.add(left(markerBar));
		top.add(Box.createVerticalStrut(4));

		// quick actions
		quick.setOpaque(false);
		quick.add(small("What next?", "One recommended next step", () -> ask("What should I do next? Recommend one next action for my account.")));
		quick.add(small("Train a skill", "Pick a skill", this::pickSkill));
		quick.add(small("Quest help", "Pick a quest", this::pickQuest));
		quick.add(small("Gear advice", "Upgrade within your budget", () -> ask("What gear upgrade should I work toward next for my account and budget?")));
		quick.add(small("Done → next step", "I finished that; give me the next step", () -> ask("I did that. What's the next step?")));
		quick.add(small("Explain more", "More detail on the last answer", () -> ask("Explain the last answer in more detail, like I'm new.")));
		top.add(left(quick));
		add(top, BorderLayout.NORTH);

		// ---------- center: chat ----------
		chat.setLayout(new GridBagLayout());
		chat.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.gridx = 0;
		row.weightx = 1;
		row.fill = GridBagConstraints.HORIZONTAL;
		row.anchor = GridBagConstraints.NORTH;
		row.insets = new Insets(0, 0, 6, 0);
		row.gridy = 0;
		chatScroll = new JScrollPane(chat, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		chatScroll.setBorder(null);
		chatScroll.getVerticalScrollBar().setUnitIncrement(16);
		chatScroll.setPreferredSize(new Dimension(PANEL_WIDTH, 380));
		add(chatScroll, BorderLayout.CENTER);
		welcome();

		// ---------- bottom: status, input ----------
		JPanel bottom = new JPanel(new BorderLayout(0, 4));
		bottom.setOpaque(false);
		JPanel statusRow = new JPanel(new BorderLayout(4, 0));
		statusRow.setOpaque(false);
		status.setFont(FontManager.getRunescapeSmallFont());
		statusRow.add(status, BorderLayout.CENTER);
		retry.setVisible(false);
		retry.addActionListener(e -> { if (lastQuestion != null) ask(lastQuestion); });
		statusRow.add(retry, BorderLayout.EAST);
		bottom.add(statusRow, BorderLayout.NORTH);

		input.setLineWrap(true);
		input.setFont(chatFont());
		input.setWrapStyleWord(true);
		input.setBorder(new EmptyBorder(4, 4, 4, 4));
		input.setToolTipText("Enter to ask, Shift+Enter for a new line");
		input.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown())
				{
					e.consume();
					submit();
				}
			}
		});
		JScrollPane inputScroll = new JScrollPane(input);
		inputScroll.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		bottom.add(inputScroll, BorderLayout.CENTER);

		JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
		actions.setOpaque(false);
		send.addActionListener(e -> submit());
		actions.add(send);
		actions.add(small("Clear chat", "Forget this conversation", this::clearChat));
		bottom.add(actions, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);

		updateProviderLabel();
		contextLabel.setText(wrap("Context not read yet — press Refresh."));
	}

	// ======================= public hooks (any thread) =======================

	public void updateProviderLabel()
	{
		SwingUtilities.invokeLater(() ->
		{
			CoachConfig c = config.get();
			if (c.provider() == CoachConfig.Provider.MOCK)
			{
				providerLabel.setText("MOCK MODE — not real AI (set up in settings)");
				providerLabel.setForeground(MOCK_COLOR);
			}
			else
			{
				providerLabel.setText((c.provider() == CoachConfig.Provider.ANTHROPIC ? "Claude · " : "OpenAI-compatible · ") + c.model()
					+ (c.webSearch() == CoachConfig.WebSearch.OFF ? "" : " + web"));
				providerLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			}
		});
	}

	public void showContext(AccountSnapshot snap)
	{
		SwingUtilities.invokeLater(() -> contextLabel.setText(wrap(Markdown.escape(ContextFormatter.summary(snap, Instant.now())))));
	}

	public void showQuestCompleted(String quest)
	{
		SwingUtilities.invokeLater(() ->
		{
			bannerText.setText(wrap("You finished " + Markdown.escape(quest) + "! Want a suggestion for what's next?"));
			banner.setVisible(true);
			revalidate();
		});
	}

	/** Any thread. Null hides the bar. */
	public void showMarker(String label)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (label == null)
			{
				markerBar.setVisible(false);
			}
			else
			{
				markerText.setText(wrap("» Going to: <b>" + Markdown.escape(label) + "</b><br>Follow the yellow arrow or open the world map."));
				markerBar.setVisible(true);
			}
			revalidate();
		});
	}

	public void showArrived()
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			status.setText("You've arrived — marker cleared.");
		});
	}

	public void refreshContext()
	{
		contextLabel.setText(wrap("Reading account…"));
		service.refresh().whenComplete((snap, err) ->
		{
			if (err != null)
			{
				SwingUtilities.invokeLater(() -> contextLabel.setText(wrap("Couldn't read account context.")));
			}
			else
			{
				showContext(snap);
			}
		});
	}

	// ======================= actions (EDT) =======================

	private void submit()
	{
		String q = input.getText().trim();
		if (q.isEmpty() || busy)
		{
			return;
		}
		input.setText("");
		ask(q);
	}

	void ask(String question)
	{
		if (busy)
		{
			return;
		}
		lastQuestion = question;
		setBusy(true);
		addBubble(Markdown.escape(question).replace("\n", "<br>"), USER_BG, null, true);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setText(config.get().webSearch() != CoachConfig.WebSearch.OFF ? "Thinking… (reading account, searching the wiki & web)" : "Thinking… (reading account, checking the wiki)");
		retry.setVisible(false);

		java.util.function.Consumer<String> progress = msg -> SwingUtilities.invokeLater(() ->
		{
			if (busy)
			{
				status.setForeground(msg.startsWith("Free-tier") ? new Color(230, 150, 40) : ColorScheme.LIGHT_GRAY_COLOR);
				status.setText(wrap(Markdown.escape(msg)));
			}
		});
		service.ask(question, progress).whenComplete((reply, err) -> SwingUtilities.invokeLater(() ->
		{
			setBusy(false);
			if (err != null)
			{
				showError(err);
				return;
			}
			status.setText(" ");
			showReply(reply);
			showContext(service.lastSnapshot());
		}));
	}

	private void showReply(CoachReply r)
	{
		StringBuilder html = new StringBuilder(Markdown.toHtml(r.getText()));
		if (!r.getLookedUp().isEmpty())
		{
			html.append("<br><br><span style='color:#9a9a9a'>Pages the plugin fetched for this answer:</span>");
			for (Source s : r.getLookedUp())
			{
				html.append("<br>• <a href=\"").append(Markdown.escape(s.getUrl())).append("\">").append(Markdown.escape(s.getTitle())).append("</a>");
				if (s.getNote() != null)
				{
					html.append(" <span style='color:#9a9a9a'>(").append(Markdown.escape(s.getNote())).append(")</span>");
				}
			}
		}
		if (r.getMarks() != null)
		{
			for (String m : r.getMarks())
			{
				html.append("<br><span style='color:#ffcd3c'>» Marked on your map: ").append(Markdown.escape(m)).append("</span>");
			}
		}
		for (String w : r.getWarnings())
		{
			html.append("<br><span style='color:#e06060'>⚠ ").append(Markdown.escape(w)).append("</span>");
		}
		boolean wikiOnly = !r.isLive() && r.getProviderLabel() != null && r.getProviderLabel().startsWith("Wiki");
		String footer = r.isLive() ? r.getProviderLabel() : wikiOnly ? r.getProviderLabel() : "MOCK — not AI output";
		addBubble(html.toString(), COACH_BG, footer, false);
		if (!r.isLive())
		{
			Component last = chat.getComponent(chat.getComponentCount() - 2);
			((javax.swing.JComponent) last).setBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, MOCK_COLOR));
		}
	}

	private void showError(Throwable err)
	{
		Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
		String title;
		String msg;
		boolean retryable;
		if (cause instanceof CoachException)
		{
			CoachException ce = (CoachException) cause;
			title = ce.getKind().getTitle();
			msg = ce.getMessage();
			retryable = ce.isRetryable();
		}
		else
		{
			title = "Something went wrong";
			msg = "Unexpected error (" + cause.getClass().getSimpleName() + "). Try again.";
			retryable = true;
		}
		status.setForeground(ERROR_COLOR);
		status.setText(wrap("<b>" + Markdown.escape(title) + "</b>"));
		addBubble("<span style='color:#e06060'><b>" + Markdown.escape(title) + ":</b> " + Markdown.escape(msg).replace("\n", "<br>") + "</span>", COACH_BG, null, false);
		retry.setVisible(retryable);
	}

	private void pickSkill()
	{
		JPopupMenu menu = new JPopupMenu();
		for (String s : skillNames)
		{
			JMenuItem item = new JMenuItem(s);
			item.addActionListener(e -> ask("What is the best way to train " + s + " at my level? Keep it beginner-friendly."));
			menu.add(item);
		}
		menu.show(quick, 0, quick.getHeight());
	}

	private void pickQuest()
	{
		JComboBox<String> box = new JComboBox<>(questNames.toArray(new String[0]));
		box.setEditable(false);
		JTextField step = new JTextField();
		JPanel p = new JPanel(new GridLayout(0, 1, 2, 2));
		p.add(new JLabel("Quest:"));
		p.add(box);
		p.add(new JLabel("Where are you stuck? (optional)"));
		p.add(step);
		int ok = JOptionPane.showConfirmDialog(this, p, "Quest help", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok != JOptionPane.OK_OPTION || box.getSelectedItem() == null)
		{
			return;
		}
		String quest = (String) box.getSelectedItem();
		onQuestSelected.accept(quest);
		String detail = step.getText().trim();
		ask("Help me with the quest \"" + quest + "\". "
			+ (detail.isEmpty() ? "Check which requirements I'm missing and what I should bring." : "I'm stuck here: " + detail + ". Explain this step and what I might be missing."));
	}

	private void editProfile()
	{
		CoachConfig c = config.get();
		JComboBox<CoachConfig.Membership> membership = new JComboBox<>(CoachConfig.Membership.values());
		membership.setSelectedItem(c.membership());
		JTextField budget = new JTextField(c.budget());
		JTextArea goals = new JTextArea(c.goals(), 3, 18);
		goals.setLineWrap(true);
		JTextArea prefs = new JTextArea(c.preferences(), 3, 18);
		prefs.setLineWrap(true);
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.add(left(new JLabel("Membership")));
		p.add(left(membership));
		p.add(left(new JLabel("Budget (e.g. 500k gp)")));
		p.add(left(budget));
		p.add(left(new JLabel("Goals")));
		p.add(left(new JScrollPane(goals)));
		p.add(left(new JLabel("Preferences")));
		p.add(left(new JScrollPane(prefs)));
		int ok = JOptionPane.showConfirmDialog(this, p, "About me", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok == JOptionPane.OK_OPTION)
		{
			configManager.setConfiguration(CoachConfig.GROUP, "membership", membership.getSelectedItem());
			configManager.setConfiguration(CoachConfig.GROUP, "budget", budget.getText().trim());
			configManager.setConfiguration(CoachConfig.GROUP, "goals", goals.getText().trim());
			configManager.setConfiguration(CoachConfig.GROUP, "preferences", prefs.getText().trim());
		}
	}

	private void showPreview()
	{
		status.setText("Reading account…");
		service.refresh().whenComplete((snap, err) -> SwingUtilities.invokeLater(() ->
		{
			status.setText(" ");
			CoachConfig c = config.get();
			String text = "Sent with every question to: " + (c.provider() == CoachConfig.Provider.MOCK ? "nobody (mock mode runs locally)" : c.provider() == CoachConfig.Provider.ANTHROPIC ? "Anthropic (api.anthropic.com)" : c.baseUrl())
				+ "\nAlso sent: the coach instructions, your question, and the last " + c.historyTurns() + " Q&A turns."
				+ "\nNever sent: your login, API key (only as an auth header), private messages, other players."
				+ (c.wikiLookups() ? "\nWiki/price lookups go to oldschool.runescape.wiki and prices.runescape.wiki with only the search words." : "")
				+ (c.webSearch() == CoachConfig.WebSearch.SERPER_GOOGLE ? "\nWeb searches go to google.serper.dev with only the search words; pages the coach opens are fetched directly." : "")
				+ (c.webSearch() == CoachConfig.WebSearch.BRAVE ? "\nWeb searches go to api.search.brave.com with only the search words; pages the coach opens are fetched directly." : "")
				+ "\nTurn categories off under Settings → OSRS Coach → Context sent to AI.\n\n"
				+ "----- account context -----\n"
				+ (snap == null ? "(unavailable)" : service.contextText(snap, c));
			showContext(snap);
			JTextArea area = new JTextArea(text, 24, 48);
			area.setEditable(false);
			area.setLineWrap(true);
			area.setWrapStyleWord(true);
			area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
			area.setCaretPosition(0);
			JOptionPane.showMessageDialog(this, new JScrollPane(area), "What gets sent to the AI", JOptionPane.INFORMATION_MESSAGE);
		}));
	}

	private void clearChat()
	{
		service.clearHistory();
		chat.removeAll();
		row.gridy = 0;
		lastQuestion = null;
		retry.setVisible(false);
		status.setText(" ");
		welcome();
	}

	// ======================= helpers =======================

	private void welcome()
	{
		addBubble("<b>Hi! I'm your OSRS coach.</b><br>Ask anything, or press <b>What next?</b> "
			+ "I'll read your levels, quests and items (see <b>What's sent?</b>) and suggest one clear step with wiki links."
			+ "<br><br><span style='color:#9a9a9a'>I only give advice — I never click, move items or play for you.</span>", COACH_BG, null, false);
	}

	private void addBubble(String html, Color bg, String footer, boolean user)
	{
		JEditorPane pane = new JEditorPane();
		pane.setEditorKit(new HTMLEditorKit());
		pane.setEditable(false);
		pane.setOpaque(true);
		pane.setBackground(bg);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setFont(chatFont());
		pane.setForeground(user ? Color.WHITE : ColorScheme.TEXT_COLOR);
		pane.setBorder(new EmptyBorder(6, 8, 6, 8));
		pane.setText("<html><body style='margin:0'>" + (user ? "<span style='color:#9a9a9a'>You</span><br>" : "") + html + "</body></html>");
		pane.addHyperlinkListener(e ->
		{
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null)
			{
				String u = e.getURL().toString();
				if (u.startsWith("https://") || u.startsWith("http://"))
				{
					LinkBrowser.browse(u);
				}
			}
		});
		row.gridy++;
		chat.add(pane, row.clone());
		JLabel foot = new JLabel(footer == null ? "" : footer);
		foot.setFont(FontManager.getRunescapeSmallFont());
		foot.setForeground(footer != null && footer.startsWith("MOCK") ? MOCK_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
		row.gridy++;
		chat.add(foot, row.clone());
		chat.revalidate();
		chat.repaint();
		SwingUtilities.invokeLater(() -> chatScroll.getVerticalScrollBar().setValue(chatScroll.getVerticalScrollBar().getMaximum()));
	}

	private java.awt.Font chatFont()
	{
		return FontManager.getRunescapeSmallFont().deriveFont((float) config.get().textSize().points);
	}

	/** Any thread. Re-applies the chat text size to existing and future messages. */
	public void applyTextSize()
	{
		SwingUtilities.invokeLater(() ->
		{
			java.awt.Font f = chatFont();
			for (Component comp : chat.getComponents())
			{
				if (comp instanceof JEditorPane)
				{
					comp.setFont(f);
				}
			}
			input.setFont(f);
			chat.revalidate();
			chat.repaint();
		});
	}

	private void setBusy(boolean b)
	{
		busy = b;
		send.setEnabled(!b);
		send.setText(b ? "…" : "Ask");
		for (Component c : quick.getComponents())
		{
			c.setEnabled(!b);
		}
	}

	private static JButton small(String text, String tip, Runnable r)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setMargin(new Insets(2, 2, 2, 2));
		b.setFocusPainted(false);
		if (tip != null)
		{
			b.setToolTipText(tip);
		}
		b.addActionListener(e -> r.run());
		return b;
	}

	private static <T extends javax.swing.JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static String wrap(String html)
	{
		return "<html><body style='width:" + (PANEL_WIDTH - 30) + "px'>" + html + "</body></html>";
	}
}
