package com.xiaoxiaomo.hadoop.mr;

import com.alibaba.fastjson.JSONObject;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.text.DecimalFormat;

/**
 *
 * 用户行为数据清洗
 *
 * 测试数据，使用remd-web中的 ugchead.log
 *
 * hadoop jar remd-hadoop-1.0-SNAPSHOT-jar-with-dependencies.jar com.xiaoxiaomo.hadoop.mr.RecommendCleaner /source/ugchead/20171022/events-.1508671857111.tmp /recommender/result_temp/20171022/ /recommender/result/20171022/
 *
 * Created by xiaoxiaomo on 2015/12/31.
 */
public class RecommendCleaner extends Configured implements Tool {

    private static Logger LOG = Logger.getLogger(RecommendCleaner.class);
    private static DecimalFormat decimalFormat = new DecimalFormat("#.00");

    @Override
    public int run(String[] args) throws Exception {

        // conf优化设置
        Configuration conf = getConf();
        conf.setBoolean("mapred.map.tasks.speculative.execution", false);
        conf.setBoolean("mapred.reduce.tasks.speculative.execution", false);
        conf.setStrings("mapred.child.java.opts","-Xmx1024m");
        conf.setInt("mapred.map.tasks",1); //自己的测试机设置小一点


        Job job = Job.getInstance(conf,"Recommend");
        job.setNumReduceTasks(1);
        job.setJarByClass(RecommendCleaner.class);

        job.setMapperClass(RecommenderMapper.class);
        job.setReducerClass(RecommenderReducer.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(DoubleWritable.class);


        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        //可以设置多输入路径
//        MultipleInputs.addInputPath( job , new Path(args[0]) , TextInputFormat.class);
//        MultipleInputs.addInputPath( job , new Path(args[1]) , DBInputFormat.class);
//        MultipleInputs.addInputPath( job , new Path(args[2]) , TextInputFormat.class);
        //history input
        for (int i = 0; i < args.length-2; i++) {
            FileInputFormat.addInputPath(job,new Path(args[i]));
        }

        //output
        FileOutputFormat.setOutputPath(job, new Path(args[args.length-2]));
        //System.exit(job.waitForCompletion(true) ? 0 : 1);

        boolean success = job.waitForCompletion(true);
        if( !success ) {
            System.exit(1);
        }

        conf = new Configuration();
        conf.set("recommender_score_max", String.valueOf(job.getCounters()
                .findCounter(RecommenderReducer.Counters.MAX).getValue()));

        Job filter =Job.getInstance(conf,"RecommenderFilter");

        filter.setJarByClass(RecommendCleaner.class);
        filter.setMapperClass(FilterMapper.class);
        filter.setOutputKeyClass(NullWritable.class);
        filter.setOutputValueClass(Text.class);

        FileInputFormat.addInputPath(filter,new Path(args[args.length-2]));

        //output
        FileOutputFormat.setOutputPath(filter, new Path(args[args.length-1]));
        return filter.waitForCompletion(true) ? 0 : 1;
    }


    public static class RecommenderMapper extends Mapper<LongWritable,Text,Text,DoubleWritable>{

        /**
         * 1003    218.75.75.133   342e12e7-eb7f-4a8b-9764-9d18b2b7439e    f94e95f6-ed1a-4fc2-b9c3-bffc873a09c9    10709   {"ugctype":"fav","userId":"10709","item":"11"}       1454147875901
         * appid	ip	mid	seid	userid	param	time
         *
         * @param key
         * @param value
         * @param context
         * @throws IOException
         * @throws InterruptedException
         */
        protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

            if ( null != value ) {    // 清理null数据
                return ;
            }

            String[] UgcLogs = value.toString().split("\t");
            //清理垃圾数据
            if ( UgcLogs.length >= 7 ){
                try {
                    Integer userId = Integer.parseInt(UgcLogs[4]);
                    String param = UgcLogs[5];  //param为一个json字符串
                    JSONObject jsonObject = JSONObject.parseObject(param);
                    String itemId =  (String) jsonObject.get("item");
                    if ( itemId != null ){
                        String ugcType = (String) jsonObject.get("ugctype");
                        Double score;

                        //行为权重
                        switch ( ugcType ){
                            case "consumer":
                                score = 1d ;
                                break;
                            case "recharge":
                                score = 2d ;
                                break;
                            case "fav":
                                score = 3d ;
                                break;
                            default:
                                score = 0d;
                        }
                        context.write(new Text(userId+"\t"+itemId),new DoubleWritable(score));

                    }
                }
                catch (Exception e){
                    LOG.error("清洗数据异常",e);
                }
            }else{
                if ( UgcLogs.length == 3){
                    context.write(new Text(UgcLogs[0]+"\t"+UgcLogs[1]),new DoubleWritable(Double.parseDouble(UgcLogs[2])));
                }

            }
        }
    }
    public static class RecommenderReducer
            extends Reducer<Text,DoubleWritable,NullWritable,Text> {

        Double result = 0d;
        long max = 0l;
        public enum Counters { MAX }
        protected void reduce(Text key, Iterable<DoubleWritable> values, Context context) throws IOException, InterruptedException {

            for ( DoubleWritable val : values ) {
            	result += val.get();
            }

            if ( context.getCounter(Counters.MAX).getValue() < result.longValue() ) {
            	context.getCounter(Counters.MAX).setValue(result.longValue());
            }
            context.write(NullWritable.get(), new Text(String.format("%s\t%s",key.toString(),result)));
        }
    }

    
    public static class FilterMapper extends Mapper<LongWritable, Text, NullWritable, Text>{
    	
    	@Override
    	protected void map(LongWritable key, Text value,Context context)
    			throws IOException, InterruptedException {
    		long max = Long.parseLong(context.getConfiguration().get("recommender_score_max"));
    		String[] strs = value.toString().split("\t");
    		if(strs.length == 3 && max > 100){
    			context.write(NullWritable.get(), new Text(String.format("%s\t%s\t%s",strs[0],strs[1],decimalFormat.format(Double.parseDouble(strs[2])*100.00/max))));
    		}else{
    			context.write(NullWritable.get(), value);
    		}
    	}
    	
    	
    }

    public static void main(String[] args)  throws Exception {
        if ( args.length < 3 ){
            LOG.error("Usage: recommend <history_in> <in> <temp> <out>");
            System.exit(2);
        }
        int exitCode = ToolRunner.run(new RecommendCleaner(), args);
        System.exit(exitCode);
    }

}