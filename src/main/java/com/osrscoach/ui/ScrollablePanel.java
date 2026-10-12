package com.osrscoach.ui;

import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/** Panel that always matches the viewport width so HTML bubbles wrap instead of scrolling sideways. */
class ScrollablePanel extends JPanel implements Scrollable
{
	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle r, int o, int d)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle r, int o, int d)
	{
		return Math.max(16, r.height - 32);
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}
}
