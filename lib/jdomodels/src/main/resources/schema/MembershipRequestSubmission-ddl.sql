CREATE TABLE `MEMBERSHIP_REQUEST_SUBMISSION` (
  `ID` bigint(20) NOT NULL,
  `TEAM_ID` bigint(20) NOT NULL,
  `USER_ID` bigint(20) NOT NULL,
  `EXPIRES_ON` bigint(20),
  `PROPERTIES` mediumblob,
  PRIMARY KEY (`ID`),
  CONSTRAINT `MEMBERSHIP_REQUEST_TEAM_FK` FOREIGN KEY (`TEAM_ID`) REFERENCES `TEAM` (`ID`),
  CONSTRAINT `MEMBERSHIP_REQUEST_USER_FK` FOREIGN KEY (`USER_ID`) REFERENCES `JDOUSERGROUP` (`ID`)
  
)