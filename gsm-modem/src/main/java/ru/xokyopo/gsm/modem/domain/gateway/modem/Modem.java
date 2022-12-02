
package ru.xokyopo.gsm.modem.domain.gateway.modem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smslib.core.Capabilities;
import org.smslib.core.Capabilities.Caps;
import org.smslib.core.Coverage;
import org.smslib.core.CreditBalance;
import ru.xokyopo.gsm.modem.domain.gateway.AbstractGateway;
import ru.xokyopo.gsm.modem.domain.gateway.modem.DeviceInformation.Modes;
import ru.xokyopo.gsm.modem.domain.gateway.modem.driver.AbstractModemDriver;
import ru.xokyopo.gsm.modem.domain.gateway.modem.driver.SerialModemDriver;
import ru.xokyopo.gsm.modem.entity.DeliveryReportMessage;
import ru.xokyopo.gsm.modem.entity.InboundMessage;
import ru.xokyopo.gsm.modem.entity.MsIsdn;
import ru.xokyopo.gsm.modem.entity.OutboundMessage;
import ru.xokyopo.gsm.modem.util.Common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.StringTokenizer;

public class Modem extends AbstractGateway
{
	static Logger logger = LoggerFactory.getLogger(Modem.class);

	DeviceInformation deviceInformation = new DeviceInformation();

	AbstractModemDriver modemDriver;

	String simPin;

	String simPin2;

	MsIsdn smscNumber;

	MessageReader messageReader;

	HashSet<String> readMessagesSet;

	public Modem(String gatewayId, String address, int port, String simPin, String simPin2, MsIsdn smscNumber, String memoryLocations)
	{
		super(2, gatewayId, "GSM Modem");
		Capabilities caps = new Capabilities();
		caps.set(Caps.CanSendMessage);
		caps.set(Caps.CanSendBinaryMessage);
		caps.set(Caps.CanSendUnicodeMessage);
		caps.set(Caps.CanSendWapMessage);
		caps.set(Caps.CanSendFlashMessage);
		caps.set(Caps.CanSendPortInfo);
		caps.set(Caps.CanSplitMessages);
		caps.set(Caps.CanRequestDeliveryStatus);
		setCapabilities(caps);
		this.modemDriver = new SerialModemDriver(this, address, port);
		if (!Common.isNullOrEmpty(memoryLocations)) this.modemDriver.setMemoryLocations(memoryLocations);
		this.simPin = simPin;
		this.simPin2 = simPin2;
		this.smscNumber = (smscNumber == null ? new MsIsdn() : smscNumber);
		this.readMessagesSet = new HashSet<>();
	}

	public Modem(String gatewayId, String... parms)
	{
		this(gatewayId, parms[0], Integer.valueOf(parms[1]), parms[2], parms[3], (Common.isNullOrEmpty(parms[4]) ? null : new MsIsdn(parms[4])), (Common.isNullOrEmpty(parms[5]) ? null : parms[5]));
	}

	public DeviceInformation getDeviceInformation()
	{
		return this.deviceInformation;
	}

	@Override
	public void _start() throws Exception
	{
		synchronized (this.modemDriver._LOCK_)
		{
			this.modemDriver.openPort();
			this.modemDriver.initializeModem();
			this.messageReader = new MessageReader(this);
			this.messageReader.start();
			logger.info(String.format("Gateway: %s: %s, SL:%s, SIG: %s / %s", toShortString(), getDeviceInformation(), this.modemDriver.getMemoryLocations(), this.modemDriver.getSignature(true), this.modemDriver.getSignature(false)));
		}
	}

	@Override
	public void _stop() throws Exception
	{
		synchronized (this.modemDriver._LOCK_)
		{
			if (this.messageReader != null)
			{
				this.messageReader.cancel();
				this.messageReader.join();
				this.messageReader = null;
			}
			this.modemDriver.closePort();
		}
	}

	public AbstractModemDriver getModemDriver()
	{
		return this.modemDriver;
	}

	public String getSimPin()
	{
		return this.simPin;
	}

	public String getSimPin2()
	{
		return this.simPin2;
	}

	public MsIsdn getSmscNumber()
	{
		return this.smscNumber;
	}

	public void setSmscNumber(MsIsdn smscNumber)
	{
		this.smscNumber = smscNumber;
	}

	public HashSet<String> getReadMessagesSet()
	{
		return this.readMessagesSet;
	}

	public void refreshDeviceInfo() throws Exception
	{
		synchronized (this.modemDriver._LOCK_)
		{
			this.modemDriver.refreshDeviceInformation();
		}
	}

	public boolean setEncoding(String encoding) throws Exception
	{
		return (this.modemDriver.atSetEncoding(encoding).isResponseOk());
	}

	public ModemResponse sendATCommand(String atCommand) throws Exception
	{
		return this.modemDriver.write(atCommand);
	}

	@Override
	public boolean _send(OutboundMessage message) throws Exception
	{
		synchronized (this.modemDriver._LOCK_)
		{
			if (getDeviceInformation().getMode() == Modes.PDU)
			{
				List<String> pdus = message.getPdus(getSmscNumber(), getNextMultipartReferenceNo(), getRequestDeliveryReport());
				for (String pdu : pdus)
				{
					int j = pdu.length() / 2;
					if (getSmscNumber() == null) {
						// Do nothing on purpose!
					} else if (getSmscNumber().getType() == MsIsdn.Type.Void) j--;
					else {
						int smscNumberLen = getSmscNumber().getAddress().length();
						if (smscNumberLen % 2 != 0) smscNumberLen++;
						int smscLen = (2 + smscNumberLen) / 2;
						j = j - smscLen - 1;
					}
					int refNo = this.modemDriver.atSendPDUMessage(j, pdu);
					if (refNo >= 0) {
						message.setGatewayId(getGatewayId());
						message.setSentDate(new Date());
						message.getOperatorMessageIds().add(String.valueOf(refNo));
						message.setSentStatus(OutboundMessage.SentStatus.Sent);
						message.setFailureCause(OutboundMessage.FailureCause.None);
					}
					else {
						message.setSentStatus(OutboundMessage.SentStatus.Failed);
						message.setFailureCause(OutboundMessage.FailureCause.GatewayFailure);
					}
				}
			}
			else
			{
				int refNo = this.modemDriver.atSendTEXTMessage(message.getRecipientAddress().getAddress(), message.getPayload().getText());
				if (refNo >= 0) {
					message.setGatewayId(getGatewayId());
					message.setSentDate(new Date());
					message.getOperatorMessageIds().add(String.valueOf(refNo));
					message.setSentStatus(OutboundMessage.SentStatus.Sent);
					message.setFailureCause(OutboundMessage.FailureCause.None);
				}
				else {
					message.setSentStatus(OutboundMessage.SentStatus.Failed);
					message.setFailureCause(OutboundMessage.FailureCause.GatewayFailure);
				}
			}
			return (message.getSentStatus() == OutboundMessage.SentStatus.Sent);
		}
	}

	@Override
	protected boolean _delete(InboundMessage message) throws Exception
	{
		synchronized (this.modemDriver._LOCK_)
		{
			this.readMessagesSet.remove(message.getSignature());
			if (message.getMemIndex() >= 0) { return this.modemDriver.atDeleteMessage(message.getMemLocation(), message.getMemIndex()).isResponseOk(); }
			if ((message.getMemIndex() == -1) && (message.getMpMemIndex().length() > 0))
			{
				StringTokenizer tokens = new StringTokenizer(message.getMpMemIndex(), ",");
				while (tokens.hasMoreTokens())
					this.modemDriver.atDeleteMessage(message.getMemLocation(), Integer.valueOf(tokens.nextToken()));
				return true;
			}
			return false;
		}
	}

	@Override
	protected DeliveryReportMessage.DeliveryStatus _queryDeliveryStatus(String operatorMessageId) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected CreditBalance _queryCreditBalance()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	protected Coverage _queryCoverage(Coverage coverage)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String toShortString()
	{
		return super.toShortString() + String.format(" [%s]", this.modemDriver.getPortInfo());
	}

	private boolean isPortAnIpAddress(String address)
	{
		try
		{
			InetAddress.getByName(address);
			return true;
		}
		catch (UnknownHostException e)
		{
			return false;
		}
	}
}
