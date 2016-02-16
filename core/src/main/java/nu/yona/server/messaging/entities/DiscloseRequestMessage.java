package nu.yona.server.messaging.entities;

import java.util.UUID;

import javax.persistence.Entity;

import nu.yona.server.analysis.entities.GoalConflictMessage;
import nu.yona.server.analysis.entities.GoalConflictMessage.Status;
import nu.yona.server.crypto.Decryptor;
import nu.yona.server.crypto.Encryptor;
import nu.yona.server.subscriptions.entities.BuddyMessage;

@Entity
public class DiscloseRequestMessage extends BuddyMessage
{
	private UUID targetGoalConflictMessageID;
	private Status status;

	// Default constructor is required for JPA
	public DiscloseRequestMessage()
	{

	}

	private DiscloseRequestMessage(UUID id, UUID requestingUserID, UUID requestingUserAnonymizedID,
			UUID targetGoalConflictMessageID, String nickname, String message)
	{
		super(id, requestingUserAnonymizedID, requestingUserID, nickname, message);
		this.targetGoalConflictMessageID = targetGoalConflictMessageID;
		this.status = Status.DISCLOSE_REQUESTED;
	}

	public UUID getTargetGoalConflictMessageID()
	{
		return targetGoalConflictMessageID;
	}

	public GoalConflictMessage getTargetGoalConflictMessage()
	{
		return (GoalConflictMessage) GoalConflictMessage.getRepository().findOne(targetGoalConflictMessageID);
	}

	public Status getStatus()
	{
		return status;
	}

	public void setStatus(Status status)
	{
		this.status = status;
	}

	@Override
	public void encrypt(Encryptor encryptor)
	{
		super.encrypt(encryptor);
	}

	@Override
	public void decrypt(Decryptor decryptor)
	{
		super.decrypt(decryptor);
	}

	public static Message createInstance(UUID requestingUserID, UUID requestingUserAnonymizedID, String requestingUserNickname,
			String message, GoalConflictMessage targetGoalConflictMessage)
	{
		return new DiscloseRequestMessage(UUID.randomUUID(), requestingUserID, requestingUserAnonymizedID,
				targetGoalConflictMessage.getID(), requestingUserNickname, message);
	}
}
