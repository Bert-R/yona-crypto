package nu.yona.server

import groovy.json.*
import spock.lang.Shared

class MessagingTest extends AbstractAppServiceIntegrationTest
{
	def 'Richard pages through his messages'()
	{
		given:
			def richardAndBob = addRichardAndBobAsBuddies()
			def richard = richardAndBob.richard
			def bob = richardAndBob.bob
			newAnalysisService.postToAnalysisEngine(richard, ["news/media"], "http://www.refdag.nl")
			newAnalysisService.postToAnalysisEngine(bob, ["Gambling"], "http://www.poker'com")
			newAnalysisService.postToAnalysisEngine(bob, ["news/media"], "http://www.refdag.nl")

		when:
			def allMessagesResponse = newAppService.getAnonymousMessages(richard)
			def firstPageMessagesResponse = newAppService.getAnonymousMessages(richard, [
				"page": 0,
				"size": 2,
				"sort": "creationTime"])
			def secondPageMessagesResponse = newAppService.getAnonymousMessages(richard, [
				"page": 1,
				"size": 2,
				"sort": "creationTime"])

		then:
			allMessagesResponse.status == 200
			allMessagesResponse.responseData._links.self.href == richard.url + newAppService.ANONYMOUS_MESSAGES_PATH_FRAGMENT + "{?page,size,sort}"
			allMessagesResponse.responseData._embedded.buddyConnectResponseMessages
			allMessagesResponse.responseData._embedded.buddyConnectResponseMessages.size() == 1
			allMessagesResponse.responseData._embedded.goalConflictMessages
			allMessagesResponse.responseData._embedded.goalConflictMessages.size() == 3

			firstPageMessagesResponse.status == 200
			firstPageMessagesResponse.responseData._links.self.href == richard.url + newAppService.ANONYMOUS_MESSAGES_PATH_FRAGMENT + "?page=0&size=2&sort=creationTime"
			!firstPageMessagesResponse.responseData._links.prev
			firstPageMessagesResponse.responseData._links.next
			!firstPageMessagesResponse.responseData._embedded.buddyConnectResponseMessages
			firstPageMessagesResponse.responseData._embedded.goalConflictMessages
			firstPageMessagesResponse.responseData._embedded.goalConflictMessages.size() == 2
			firstPageMessagesResponse.responseData.page.totalElements == 4

			secondPageMessagesResponse.status == 200
			secondPageMessagesResponse.responseData._links.self.href == richard.url + newAppService.ANONYMOUS_MESSAGES_PATH_FRAGMENT + "?page=1&size=2&sort=creationTime"
			secondPageMessagesResponse.responseData._links.prev
			!secondPageMessagesResponse.responseData._links.next
			secondPageMessagesResponse.responseData._embedded.buddyConnectResponseMessages
			secondPageMessagesResponse.responseData._embedded.buddyConnectResponseMessages.size() == 1
			secondPageMessagesResponse.responseData._embedded.goalConflictMessages
			secondPageMessagesResponse.responseData._embedded.goalConflictMessages.size() == 1
			secondPageMessagesResponse.responseData.page.totalElements == 4
	}

	def 'Bob tries to delete Richard\'s buddy request before it is processed'()
	{
		given:
			def richard = addRichard()
			def bob = addBob()
			newAppService.sendBuddyConnectRequest(richard, bob)
			def messageURL = newAppService.getDirectMessages(bob).responseData._embedded.buddyConnectRequestMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, bob.password)

		then:
			response.status == 400
			response.responseData?.code == "error.cannot.delete.unprocessed.message"
			newAppService.getDirectMessages(bob).responseData._embedded.buddyConnectRequestMessages.size() == 1
	}

	def 'Bob deletes Richard\'s buddy request after it is processed'()
	{
		given:
			def richard = addRichard()
			def bob = addBob()
			newAppService.sendBuddyConnectRequest(richard, bob)
			def acceptURL = newAppService.fetchBuddyConnectRequestMessage(bob).acceptURL
			newAppService.postMessageActionWithPassword(acceptURL, ["message" : "Yes, great idea!"], bob.password)
			def messageURL = newAppService.getDirectMessages(bob).responseData._embedded.buddyConnectRequestMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, bob.password)

		then:
			response.status == 200
			newAppService.getDirectMessages(bob).responseData._embedded?.buddyConnectRequestMessages == null
	}

	def 'Richard tries to delete Bob\'s buddy acceptance before it is processed'()
	{
		given:
			def richard = addRichard()
			def bob = addBob()
			newAppService.sendBuddyConnectRequest(richard, bob)
			def acceptURL = newAppService.fetchBuddyConnectRequestMessage(bob).acceptURL
			newAppService.postMessageActionWithPassword(acceptURL, ["message" : "Yes, great idea!"], bob.password)
			def messageURL = newAppService.getAnonymousMessages(richard).responseData._embedded.buddyConnectResponseMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, richard.password)

		then:
			response.status == 400
			response.responseData?.code == "error.cannot.delete.unprocessed.message"
			newAppService.getAnonymousMessages(richard).responseData._embedded.buddyConnectResponseMessages.size() == 1
	}

	def 'Richard deletes Bob\'s buddy acceptance after it is processed'()
	{
		given:
			def richard = addRichard()
			def bob = addBob()
			newAppService.sendBuddyConnectRequest(richard, bob)
			def acceptURL = newAppService.fetchBuddyConnectRequestMessage(bob).acceptURL
			newAppService.postMessageActionWithPassword(acceptURL, ["message" : "Yes, great idea!"], bob.password)
			def processURL = newAppService.fetchBuddyConnectResponseMessage(richard).processURL
			def processResponse = newAppService.postMessageActionWithPassword(processURL, [ : ], richard.password)
			def messageURL = newAppService.getAnonymousMessages(richard).responseData._embedded.buddyConnectResponseMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, richard.password)

		then:
			response.status == 200
			newAppService.getDirectMessages(richard).responseData._embedded?.buddyConnectRequestMessages == null
	}

	def 'Richard deletes a goal conflict message. After that, Bob still has it'()
	{
		given:
			def richardAndBob = addRichardAndBobAsBuddies()
			def richard = richardAndBob.richard
			def bob = richardAndBob.bob
			newAnalysisService.postToAnalysisEngine(richard, ["news/media"], "http://www.refdag.nl")
			def messageURL = newAppService.getAnonymousMessages(richard).responseData._embedded.goalConflictMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, richard.password)
			
		then:
			response.status == 200
			newAppService.getAnonymousMessages(richard).responseData._embedded?.goalConflictMessages == null

			newAppService.getAnonymousMessages(bob).responseData._embedded.goalConflictMessages.size() == 1
	}

	def 'Bob deletes a goal conflict message. After that, Richard still has it'()
	{
		given:
			def richardAndBob = addRichardAndBobAsBuddies()
			def richard = richardAndBob.richard
			def bob = richardAndBob.bob
			newAnalysisService.postToAnalysisEngine(richard, ["news/media"], "http://www.refdag.nl")
			def messageURL = newAppService.getAnonymousMessages(bob).responseData._embedded.goalConflictMessages[0]._links.self.href

		when:
			def response = newAppService.deleteResourceWithPassword(messageURL, bob.password)
			
		then:
			response.status == 200
			newAppService.getAnonymousMessages(richard).responseData._embedded.goalConflictMessages.size() == 1

			newAppService.getAnonymousMessages(bob).responseData._embedded?.goalConflictMessages == null
	}
}
