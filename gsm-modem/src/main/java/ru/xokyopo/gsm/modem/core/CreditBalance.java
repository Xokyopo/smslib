
package ru.xokyopo.gsm.modem.core;

import ru.xokyopo.gsm.modem.util.Common;

import java.util.Date;

public class CreditBalance {
	double credits;

	Date lastUpdate;

	public CreditBalance() {
		this.credits = 0;
		this.lastUpdate = Common.getMinDate();
	}

	public double getCredits() {
		return this.credits;
	}

	public void setCredits(double credits) {
		this.credits = credits;
		this.lastUpdate = new Date();
	}

	public Date getLastUpdate() {
		return (Date) this.lastUpdate.clone();
	}

	@Override
	public String toString() {
		StringBuffer b = new StringBuffer(256);
		b.append(String.format("Credits: %f%n", getCredits()));
		b.append(String.format("Last update: %s%n", getLastUpdate().toString()));
		return b.toString();
	}
}
