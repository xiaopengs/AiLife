CREATE TABLE `content_items` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`themeId` int,
	`themeName` varchar(120) NOT NULL DEFAULT '未归类',
	`contentType` enum('教程','清单','案例','观点','复盘') NOT NULL DEFAULT '教程',
	`status` enum('idea','draft','review','scheduled','published') NOT NULL DEFAULT 'idea',
	`title` varchar(180) NOT NULL,
	`brief` text NOT NULL,
	`body` text NOT NULL,
	`tags` text NOT NULL,
	`coverPoints` text NOT NULL,
	`assetNotes` text NOT NULL,
	`reviewNotes` text NOT NULL,
	`scheduledAt` timestamp,
	`publishedAt` timestamp,
	`publishedUrl` varchar(500),
	`publishResult` text NOT NULL,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `content_items_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `performance_metrics` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`contentId` int NOT NULL,
	`impressions` int NOT NULL DEFAULT 0,
	`likes` int NOT NULL DEFAULT 0,
	`comments` int NOT NULL DEFAULT 0,
	`collects` int NOT NULL DEFAULT 0,
	`shares` int NOT NULL DEFAULT 0,
	`followersGained` int NOT NULL DEFAULT 0,
	`recordedAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `performance_metrics_id` PRIMARY KEY(`id`),
	CONSTRAINT `performance_metrics_content_unique` UNIQUE(`contentId`)
);
--> statement-breakpoint
CREATE TABLE `skill_themes` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`name` varchar(120) NOT NULL,
	`description` text NOT NULL,
	`audienceNeed` text NOT NULL,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `skill_themes_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
CREATE TABLE `strategy_profiles` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`accountName` varchar(120) NOT NULL,
	`positioning` text NOT NULL,
	`targetAudience` text NOT NULL,
	`corePromise` text NOT NULL,
	`brandVoice` text NOT NULL,
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `strategy_profiles_id` PRIMARY KEY(`id`),
	CONSTRAINT `strategy_profiles_user_unique` UNIQUE(`userId`)
);
--> statement-breakpoint
CREATE INDEX `content_items_user_status_idx` ON `content_items` (`userId`,`status`);--> statement-breakpoint
CREATE INDEX `content_items_schedule_idx` ON `content_items` (`userId`,`scheduledAt`);--> statement-breakpoint
CREATE INDEX `performance_metrics_user_idx` ON `performance_metrics` (`userId`);--> statement-breakpoint
CREATE INDEX `skill_themes_user_idx` ON `skill_themes` (`userId`);